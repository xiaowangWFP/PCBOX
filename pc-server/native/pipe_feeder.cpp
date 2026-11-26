#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0600
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Native 8MB-pipe ffmpeg -> softcam.dll feeder.
// Fixes Windows Java ProcessBuilder's 4KB pipe bottleneck that capped 1080p BGR24 at ~9 FPS,
// enabling full 30/60 FPS delivery at 480p, 720p, and 1080p with zero JNA per-frame copying.

namespace {

typedef void (__cdecl *PFN_scSendFrame)(void* camera, const void* imageBits);

struct FeederSession {
    std::atomic<bool> stopRequested{false};
    std::atomic<bool> running{false};
    std::atomic<uint64_t> framesSent{0};
    std::atomic<double> actualFps{0.0};

    HANDLE hProcess = nullptr;
    HANDLE hOutRead = nullptr;
    HANDLE hErrRead = nullptr;
    std::thread readerThread;
    std::thread errThread;

    std::mutex errMu;
    std::string lastError;
};

std::string toUtf8(const std::string& raw) {
    return raw;
}

void drainStderrThread(FeederSession* s) {
    char buf[1024];
    std::string pending;
    while (!s->stopRequested.load(std::memory_order_relaxed)) {
        DWORD got = 0;
        if (!ReadFile(s->hErrRead, buf, sizeof(buf) - 1, &got, nullptr) || got == 0) {
            break;
        }
        buf[got] = '\0';
        pending.append(buf, got);
        size_t pos;
        while ((pos = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, pos);
            pending.erase(0, pos + 1);
            while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) {
                line.pop_back();
            }
            if (!line.empty()) {
                std::lock_guard<std::mutex> lock(s->errMu);
                s->lastError = line;
            }
        }
    }
}

void readerLoopThread(FeederSession* s, void* scHandle, PFN_scSendFrame pSend, uint32_t frameBytes) {
    std::vector<uint8_t> frame(frameBytes);
    LARGE_INTEGER freq{}, statStart{};
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&statStart);
    int statFrames = 0;

    while (!s->stopRequested.load(std::memory_order_relaxed)) {
        uint32_t off = 0;
        bool ok = true;
        while (off < frameBytes) {
            if (s->stopRequested.load(std::memory_order_relaxed)) {
                ok = false;
                break;
            }
            DWORD got = 0;
            if (!ReadFile(s->hOutRead, frame.data() + off, frameBytes - off, &got, nullptr) || got == 0) {
                ok = false;
                break;
            }
            off += got;
        }
        if (!ok) {
            break;
        }

        if (scHandle && pSend) {
            pSend(scHandle, frame.data());
        }
        s->framesSent.fetch_add(1, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(s->errMu);
            if (!s->lastError.empty()) {
                s->lastError.clear();
            }
        }
        statFrames++;

        LARGE_INTEGER now{};
        QueryPerformanceCounter(&now);
        double elapsed = static_cast<double>(now.QuadPart - statStart.QuadPart) / static_cast<double>(freq.QuadPart);
        if (elapsed >= 1.0) {
            s->actualFps.store(static_cast<double>(statFrames) / elapsed, std::memory_order_relaxed);
            statFrames = 0;
            statStart = now;
        }
    }

    s->actualFps.store(0.0, std::memory_order_relaxed);
    s->running.store(false, std::memory_order_release);
}

} // namespace

extern "C" {

__declspec(dllexport) void* __cdecl feeder_start(
        const wchar_t* cmdLine,
        int width,
        int height,
        uint64_t softcamHandlePeer,
        const wchar_t* softcamDllPath) {
    if (!cmdLine || width <= 0 || height <= 0 || softcamHandlePeer == 0 || !softcamDllPath) {
        return nullptr;
    }

    HMODULE hSoftcam = LoadLibraryW(softcamDllPath);
    if (!hSoftcam) {
        return nullptr;
    }
    auto pSend = reinterpret_cast<PFN_scSendFrame>(GetProcAddress(hSoftcam, "scSendFrame"));
    if (!pSend) {
        return nullptr;
    }

    SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
    HANDLE hOutRead = nullptr, hOutWrite = nullptr;
    HANDLE hErrRead = nullptr, hErrWrite = nullptr;

    // 8 MB pipe buffer prevents Windows pipe micro-stalls on 1080p BGR24 frames (6.22 MB/frame)
    if (!CreatePipe(&hOutRead, &hOutWrite, &sa, 8 * 1024 * 1024)) {
        return nullptr;
    }
    SetHandleInformation(hOutRead, HANDLE_FLAG_INHERIT, 0);

    if (!CreatePipe(&hErrRead, &hErrWrite, &sa, 64 * 1024)) {
        CloseHandle(hOutRead);
        CloseHandle(hOutWrite);
        return nullptr;
    }
    SetHandleInformation(hErrRead, HANDLE_FLAG_INHERIT, 0);

    // Restrict inherited handles strictly to the stdout/stderr pipe write ends
    // so ffmpeg.exe never inherits parent Java sockets such as ADB port 5037.
    HANDLE inheritHandles[2] = {hOutWrite, hErrWrite};
    SIZE_T attrSize = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attrSize);
    std::vector<uint8_t> attrBuf(attrSize);
    LPPROC_THREAD_ATTRIBUTE_LIST attrList = attrBuf.empty()
            ? nullptr
            : reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attrBuf.data());
    bool attrInitialized = attrList != nullptr
            && InitializeProcThreadAttributeList(attrList, 1, 0, &attrSize) != FALSE;
    bool attrReady = attrInitialized
            && UpdateProcThreadAttribute(
                    attrList,
                    0,
                    PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                    inheritHandles,
                    sizeof(inheritHandles),
                    nullptr,
                    nullptr) != FALSE;

    STARTUPINFOEXW si{};
    si.StartupInfo.cb = attrReady ? sizeof(STARTUPINFOEXW) : sizeof(STARTUPINFOW);
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdOutput = hOutWrite;
    si.StartupInfo.hStdError = hErrWrite;
    si.StartupInfo.hStdInput = nullptr;
    si.lpAttributeList = attrReady ? attrList : nullptr;

    DWORD creationFlags = CREATE_NO_WINDOW | (attrReady ? EXTENDED_STARTUPINFO_PRESENT : 0);
    std::wstring cmdCopy(cmdLine);
    PROCESS_INFORMATION pi{};
    BOOL created = CreateProcessW(
            nullptr,
            &cmdCopy[0],
            nullptr,
            nullptr,
            TRUE,
            creationFlags,
            nullptr,
            nullptr,
            &si.StartupInfo,
            &pi);

    if (attrInitialized) {
        DeleteProcThreadAttributeList(attrList);
    }

    CloseHandle(hOutWrite);
    CloseHandle(hErrWrite);

    if (!created) {
        CloseHandle(hOutRead);
        CloseHandle(hErrRead);
        return nullptr;
    }

    CloseHandle(pi.hThread);

    auto* session = new FeederSession();
    session->hProcess = pi.hProcess;
    session->hOutRead = hOutRead;
    session->hErrRead = hErrRead;
    session->running.store(true, std::memory_order_release);

    void* scHandle = reinterpret_cast<void*>(static_cast<uintptr_t>(softcamHandlePeer));
    uint32_t frameBytes = static_cast<uint32_t>(width) * static_cast<uint32_t>(height) * 3u;

    session->errThread = std::thread(drainStderrThread, session);
    session->readerThread = std::thread(readerLoopThread, session, scHandle, pSend, frameBytes);
    return session;
}

__declspec(dllexport) int __cdecl feeder_is_running(void* handle) {
    if (!handle) return 0;
    auto* s = static_cast<FeederSession*>(handle);
    return s->running.load(std::memory_order_acquire) ? 1 : 0;
}

__declspec(dllexport) uint64_t __cdecl feeder_get_frames(void* handle) {
    if (!handle) return 0;
    return static_cast<FeederSession*>(handle)->framesSent.load(std::memory_order_relaxed);
}

__declspec(dllexport) double __cdecl feeder_get_fps(void* handle) {
    if (!handle) return 0.0;
    return static_cast<FeederSession*>(handle)->actualFps.load(std::memory_order_relaxed);
}

__declspec(dllexport) int __cdecl feeder_get_error(void* handle, char* outBuf, int bufCap) {
    if (!handle || !outBuf || bufCap <= 0) return 0;
    auto* s = static_cast<FeederSession*>(handle);
    std::lock_guard<std::mutex> lock(s->errMu);
    if (s->lastError.empty()) {
        outBuf[0] = '\0';
        return 0;
    }
    size_t n = (std::min)(s->lastError.size(), static_cast<size_t>(bufCap - 1));
    std::memcpy(outBuf, s->lastError.data(), n);
    outBuf[n] = '\0';
    return static_cast<int>(n);
}

__declspec(dllexport) void __cdecl feeder_stop(void* handle) {
    if (!handle) return;
    auto* s = static_cast<FeederSession*>(handle);
    s->stopRequested.store(true, std::memory_order_release);

    if (s->hProcess) {
        TerminateProcess(s->hProcess, 0);
        WaitForSingleObject(s->hProcess, 500);
        CloseHandle(s->hProcess);
        s->hProcess = nullptr;
    }
    if (s->hOutRead) {
        CancelIoEx(s->hOutRead, nullptr);
    }
    if (s->hErrRead) {
        CancelIoEx(s->hErrRead, nullptr);
    }
    if (s->readerThread.joinable()) {
        s->readerThread.join();
    }
    if (s->errThread.joinable()) {
        s->errThread.join();
    }
    if (s->hOutRead) {
        CloseHandle(s->hOutRead);
        s->hOutRead = nullptr;
    }
    if (s->hErrRead) {
        CloseHandle(s->hErrRead);
        s->hErrRead = nullptr;
    }
    delete s;
}

} // extern "C"
