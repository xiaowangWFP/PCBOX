#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <evntrace.h>
#include <evntcons.h>
#include <dxgi.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

// Clean-room FPS probe combining:
// 1) D3DKMTQueryStatistics (Type = 7, VidPnSource flip/present counter) — works on Win10/11 WITHOUT Admin.
// 2) Windows ETW (DXGI / D3D9 / DxgKrnl Present events, inspired by aneeskhan47/fps-overlay) — provides
//    per-process appFps when elevated or when ETW Present events are delivered to the session.

namespace {

// {CA11C036-0102-4A2D-A6AD-F03CFED5D3C9} Microsoft-Windows-DXGI
const GUID DXGI_PROVIDER = {
    0xCA11C036, 0x0102, 0x4A2D, {0xA6, 0xAD, 0xF0, 0x3C, 0xFE, 0xD5, 0xD3, 0xC9}
};
// {783ACA0A-790E-4D7F-8451-AA850511C6B9} Microsoft-Windows-D3D9
const GUID D3D9_PROVIDER = {
    0x783ACA0A, 0x790E, 0x4D7F, {0x84, 0x51, 0xAA, 0x85, 0x05, 0x11, 0xC6, 0xB9}
};
// {802EC45A-1E99-4B83-9920-87C98277BA9D} Microsoft-Windows-DxgKrnl
const GUID DXGKRNL_PROVIDER = {
    0x802EC45A, 0x1E99, 0x4B83, {0x99, 0x20, 0x87, 0xC9, 0x82, 0x77, 0xBA, 0x9D}
};

constexpr wchar_t SESSION_NAME[] = L"PhoneConsoleFpsProbe";

typedef LONG NTSTATUS;
typedef struct _D3DKMT_QUERYSTATISTICS {
    ULONG Type;
    LUID AdapterLuid;
    HANDLE hProcess;
    BYTE QueryResult[4096];
    union {
        struct { ULONG VidPnSourceId; } QueryVidPnSource;
        struct { ULONG VidPnSourceId; } QueryProcessVidPnSource;
    };
} D3DKMT_QUERYSTATISTICS;

typedef NTSTATUS (WINAPI *PFN_D3DKMTQueryStatistics)(D3DKMT_QUERYSTATISTICS*);

struct VidPnCounter {
    LUID luid;
    ULONG sourceId;
    uint32_t lastCount;
    bool initialized;
};

struct FrameBucket {
    std::mutex mu;
    std::vector<int64_t> timestamps;

    void push(int64_t qpc) {
        std::lock_guard<std::mutex> lock(mu);
        timestamps.push_back(qpc);
    }

    double computeFps(int64_t now, int64_t freq) {
        std::lock_guard<std::mutex> lock(mu);
        int64_t cutoff = now - freq;
        size_t keep = 0;
        for (size_t i = 0; i < timestamps.size(); ++i) {
            if (timestamps[i] >= cutoff) {
                timestamps[keep++] = timestamps[i];
            }
        }
        timestamps.resize(keep);
        if (keep < 2) {
            return keep == 1 ? 1.0 : 0.0;
        }
        double span = static_cast<double>(timestamps.back() - timestamps.front()) / static_cast<double>(freq);
        if (span <= 0.05) {
            return static_cast<double>(keep);
        }
        return static_cast<double>(keep - 1) / span;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mu);
        timestamps.clear();
    }
};

std::atomic<bool> g_running{false};
std::atomic<uint32_t> g_targetPid{0};
std::thread g_etwThread;
TRACEHANDLE g_sessionHandle = 0;
TRACEHANDLE g_traceHandle = INVALID_PROCESSTRACE_HANDLE;
int64_t g_qpcFreq = 0;

FrameBucket g_etwAppBucket;
FrameBucket g_etwScreenBucket;

std::mutex g_kmtMu;
HMODULE g_gdi32 = nullptr;
PFN_D3DKMTQueryStatistics g_pQueryStats = nullptr;
std::vector<VidPnCounter> g_counters;
int64_t g_lastKmtQpc = 0;
double g_lastKmtFps = 0.0;

void initKmtCounters() {
    std::lock_guard<std::mutex> lock(g_kmtMu);
    g_counters.clear();
    g_lastKmtFps = 0.0;
    LARGE_INTEGER now{};
    QueryPerformanceCounter(&now);
    g_lastKmtQpc = now.QuadPart;

    if (!g_gdi32) {
        g_gdi32 = LoadLibraryA("gdi32.dll");
    }
    if (g_gdi32 && !g_pQueryStats) {
        g_pQueryStats = reinterpret_cast<PFN_D3DKMTQueryStatistics>(
            GetProcAddress(g_gdi32, "D3DKMTQueryStatistics"));
    }
    if (!g_pQueryStats) return;

    IDXGIFactory* fac = nullptr;
    if (FAILED(CreateDXGIFactory(__uuidof(IDXGIFactory), reinterpret_cast<void**>(&fac))) || !fac) {
        return;
    }

    std::vector<LUID> seenLuids;
    for (UINT i = 0; i < 8; ++i) {
        IDXGIAdapter* adp = nullptr;
        if (fac->EnumAdapters(i, &adp) != S_OK || !adp) break;
        DXGI_ADAPTER_DESC desc{};
        if (SUCCEEDED(adp->GetDesc(&desc))) {
            bool dup = false;
            for (const LUID& l : seenLuids) {
                if (l.LowPart == desc.AdapterLuid.LowPart && l.HighPart == desc.AdapterLuid.HighPart) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                seenLuids.push_back(desc.AdapterLuid);
                for (ULONG src = 0; src < 4; ++src) {
                    D3DKMT_QUERYSTATISTICS qs{};
                    qs.Type = 7; // D3DKMT_QUERYSTATISTICS_VIDPNSOURCE
                    qs.AdapterLuid = desc.AdapterLuid;
                    qs.QueryVidPnSource.VidPnSourceId = src;
                    if (g_pQueryStats(&qs) == 0) {
                        uint32_t val = *reinterpret_cast<uint32_t*>(qs.QueryResult);
                        g_counters.push_back({desc.AdapterLuid, src, val, true});
                    }
                }
            }
        }
        adp->Release();
    }
    fac->Release();
}

double sampleKmtScreenFps(int64_t nowQpc) {
    std::lock_guard<std::mutex> lock(g_kmtMu);
    if (!g_pQueryStats || g_counters.empty() || g_qpcFreq <= 0) {
        return -1.0;
    }

    double elapsed = static_cast<double>(nowQpc - g_lastKmtQpc) / static_cast<double>(g_qpcFreq);
    if (elapsed < 0.08) {
        return g_lastKmtFps;
    }

    uint32_t maxDelta = 0;
    for (VidPnCounter& c : g_counters) {
        D3DKMT_QUERYSTATISTICS qs{};
        qs.Type = 7; // D3DKMT_QUERYSTATISTICS_VIDPNSOURCE
        qs.AdapterLuid = c.luid;
        qs.QueryVidPnSource.VidPnSourceId = c.sourceId;
        if (g_pQueryStats(&qs) == 0) {
            uint32_t cur = *reinterpret_cast<uint32_t*>(qs.QueryResult);
            if (c.initialized && cur >= c.lastCount) {
                uint32_t delta = cur - c.lastCount;
                if (delta > maxDelta) {
                    maxDelta = delta;
                }
            }
            c.lastCount = cur;
            c.initialized = true;
        }
    }

    g_lastKmtQpc = nowQpc;
    g_lastKmtFps = static_cast<double>(maxDelta) / elapsed;
    return g_lastKmtFps;
}

inline bool guidEquals(const GUID& a, const GUID& b) {
    return std::memcmp(&a, &b, sizeof(GUID)) == 0;
}

VOID WINAPI onEtwEvent(PEVENT_RECORD ev) {
    if (!g_running.load(std::memory_order_relaxed) || !ev) return;

    const GUID& prov = ev->EventHeader.ProviderId;
    const USHORT id = ev->EventHeader.EventDescriptor.Id;
    const uint32_t pid = ev->EventHeader.ProcessId;

    bool isPresent = false;
    if (guidEquals(prov, DXGI_PROVIDER)) {
        // DXGI Present Start (42)
        isPresent = (id == 42);
    } else if (guidEquals(prov, D3D9_PROVIDER)) {
        // D3D9 Present Start (1)
        isPresent = (id == 1);
    } else if (guidEquals(prov, DXGKRNL_PROVIDER)) {
        // DxgKrnl Present (184) / Flip (168) / Blt (166)
        isPresent = (id == 184 || id == 168);
    }

    if (!isPresent) return;

    LARGE_INTEGER qpc{};
    QueryPerformanceCounter(&qpc);
    g_etwScreenBucket.push(qpc.QuadPart);

    uint32_t target = g_targetPid.load(std::memory_order_relaxed);
    if (target != 0 && pid == target) {
        g_etwAppBucket.push(qpc.QuadPart);
    }
}

void stopEtwSession() {
    if (g_traceHandle != INVALID_PROCESSTRACE_HANDLE) {
        CloseTrace(g_traceHandle);
        g_traceHandle = INVALID_PROCESSTRACE_HANDLE;
    }

    const size_t propSize = sizeof(EVENT_TRACE_PROPERTIES) + sizeof(SESSION_NAME) + 64;
    std::vector<uint8_t> buf(propSize, 0);
    auto* props = reinterpret_cast<EVENT_TRACE_PROPERTIES*>(buf.data());
    props->Wnode.BufferSize = static_cast<ULONG>(propSize);
    props->LoggerNameOffset = sizeof(EVENT_TRACE_PROPERTIES);
    std::memcpy(buf.data() + props->LoggerNameOffset, SESSION_NAME, sizeof(SESSION_NAME));

    ControlTraceW(g_sessionHandle, SESSION_NAME, props, EVENT_TRACE_CONTROL_STOP);
    g_sessionHandle = 0;
}

void etwWorker() {
    stopEtwSession();

    const size_t propSize = sizeof(EVENT_TRACE_PROPERTIES) + sizeof(SESSION_NAME) + 64;
    std::vector<uint8_t> buf(propSize, 0);
    auto* props = reinterpret_cast<EVENT_TRACE_PROPERTIES*>(buf.data());
    props->Wnode.BufferSize = static_cast<ULONG>(propSize);
    props->Wnode.Flags = WNODE_FLAG_TRACED_GUID;
    props->Wnode.ClientContext = 1; // QPC
    props->LogFileMode = EVENT_TRACE_REAL_TIME_MODE;
    props->BufferSize = 64;
    props->MinimumBuffers = 4;
    props->MaximumBuffers = 16;
    props->FlushTimer = 1;
    props->LoggerNameOffset = sizeof(EVENT_TRACE_PROPERTIES);
    std::memcpy(buf.data() + props->LoggerNameOffset, SESSION_NAME, sizeof(SESSION_NAME));

    ULONG status = StartTraceW(&g_sessionHandle, SESSION_NAME, props);
    if (status != ERROR_SUCCESS) {
        return;
    }

    EnableTraceEx2(g_sessionHandle, &DXGI_PROVIDER, EVENT_CONTROL_CODE_ENABLE_PROVIDER,
                   TRACE_LEVEL_INFORMATION, 0, 0, 0, nullptr);
    EnableTraceEx2(g_sessionHandle, &D3D9_PROVIDER, EVENT_CONTROL_CODE_ENABLE_PROVIDER,
                   TRACE_LEVEL_INFORMATION, 0, 0, 0, nullptr);
    EnableTraceEx2(g_sessionHandle, &DXGKRNL_PROVIDER, EVENT_CONTROL_CODE_ENABLE_PROVIDER,
                   TRACE_LEVEL_INFORMATION, 0x10, 0, 0, nullptr);

    EVENT_TRACE_LOGFILEW logfile{};
    logfile.LoggerName = const_cast<LPWSTR>(SESSION_NAME);
    logfile.ProcessTraceMode = PROCESS_TRACE_MODE_REAL_TIME | PROCESS_TRACE_MODE_EVENT_RECORD;
    logfile.EventRecordCallback = onEtwEvent;

    g_traceHandle = OpenTraceW(&logfile);
    if (g_traceHandle == INVALID_PROCESSTRACE_HANDLE) {
        stopEtwSession();
        return;
    }

    ProcessTrace(&g_traceHandle, 1, nullptr, nullptr);
}

} // namespace

extern "C" {

__declspec(dllexport) int __cdecl fps_start() {
    if (g_running.exchange(true)) {
        return 1;
    }
    LARGE_INTEGER freq{};
    QueryPerformanceFrequency(&freq);
    g_qpcFreq = freq.QuadPart;

    g_etwAppBucket.clear();
    g_etwScreenBucket.clear();
    initKmtCounters();

    g_etwThread = std::thread(etwWorker);
    return 1;
}

__declspec(dllexport) void __cdecl fps_set_target_pid(unsigned int pid) {
    if (g_targetPid.exchange(pid) != pid) {
        g_etwAppBucket.clear();
    }
}

__declspec(dllexport) int __cdecl fps_get(double* outAppFps, double* outScreenFps) {
    if (!g_running.load(std::memory_order_relaxed) || g_qpcFreq <= 0) {
        if (outAppFps) *outAppFps = -1.0;
        if (outScreenFps) *outScreenFps = -1.0;
        return 0;
    }

    LARGE_INTEGER now{};
    QueryPerformanceCounter(&now);

    double etwScreen = g_etwScreenBucket.computeFps(now.QuadPart, g_qpcFreq);
    double kmtScreen = sampleKmtScreenFps(now.QuadPart);

    // Prefer ETW screen FPS if ETW events are actively arriving (e.g. elevated);
    // otherwise use D3DKMTQueryStatistics VidPnSource counter (works non-admin).
    double screenFps = (etwScreen > 0.0) ? etwScreen : kmtScreen;

    double appFps = -1.0;
    if (g_targetPid.load(std::memory_order_relaxed) != 0) {
        double etwApp = g_etwAppBucket.computeFps(now.QuadPart, g_qpcFreq);
        if (etwApp > 0.0) {
            appFps = etwApp;
        }
    }

    if (outAppFps) *outAppFps = appFps;
    if (outScreenFps) *outScreenFps = screenFps;
    return 1;
}

__declspec(dllexport) void __cdecl fps_stop() {
    if (!g_running.exchange(false)) {
        return;
    }
    stopEtwSession();
    if (g_etwThread.joinable()) {
        g_etwThread.join();
    }
}

} // extern "C"
