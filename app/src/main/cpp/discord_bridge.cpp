#define DISCORDPP_IMPLEMENTATION
#include "discord_bridge.h"
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>

#define LOG_TAG "DiscordBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

DiscordBridge g_discordBridge;
static JavaVM* g_javaVm = nullptr;
static jclass g_managerClass = nullptr;

static void NotifyJavaError(const std::string& message) {
    if (!g_javaVm || !g_managerClass) {
        LOGW("NotifyJavaError: Java callback not ready: %s", message.c_str());
        return;
    }

    JNIEnv* env = nullptr;
    bool shouldDetach = false;
    const jint envResult = g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envResult == JNI_EDETACHED) {
        if (g_javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("NotifyJavaError: failed to attach Java thread");
            return;
        }
        shouldDetach = true;
    } else if (envResult != JNI_OK) {
        LOGE("NotifyJavaError: failed to get Java environment");
        return;
    }

    const jmethodID method = env->GetStaticMethodID(
        g_managerClass,
        "onNativeError",
        "(Ljava/lang/String;)V"
    );
    if (!method) {
        LOGE("NotifyJavaError: callback method not found");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (shouldDetach) g_javaVm->DetachCurrentThread();
        return;
    }

    jstring javaMessage = env->NewStringUTF(message.c_str());
    env->CallStaticVoidMethod(g_managerClass, method, javaMessage);
    env->DeleteLocalRef(javaMessage);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (shouldDetach) g_javaVm->DetachCurrentThread();
}

static void NotifyJavaAuthorized(const std::string& accessToken) {
    if (!g_javaVm || !g_managerClass) {
        LOGW("NotifyJavaAuthorized: Java callback not ready");
        return;
    }

    JNIEnv* env = nullptr;
    bool shouldDetach = false;
    const jint envResult = g_javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envResult == JNI_EDETACHED) {
        if (g_javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("NotifyJavaAuthorized: failed to attach Java thread");
            return;
        }
        shouldDetach = true;
    } else if (envResult != JNI_OK) {
        LOGE("NotifyJavaAuthorized: failed to get Java environment");
        return;
    }

    const jmethodID method = env->GetStaticMethodID(
        g_managerClass,
        "onNativeAuthorized",
        "(Ljava/lang/String;)V"
    );
    if (!method) {
        LOGE("NotifyJavaAuthorized: callback method not found");
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (shouldDetach) g_javaVm->DetachCurrentThread();
        return;
    }

    jstring javaToken = env->NewStringUTF(accessToken.c_str());
    env->CallStaticVoidMethod(g_managerClass, method, javaToken);
    env->DeleteLocalRef(javaToken);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (shouldDetach) g_javaVm->DetachCurrentThread();
}

static std::string JsonEscape(const std::string& value) {
    std::ostringstream out;
    for (char ch : value) {
        switch (ch) {
            case '\\':
                out << "\\\\";
                break;
            case '"':
                out << "\\\"";
                break;
            case '\b':
                out << "\\b";
                break;
            case '\f':
                out << "\\f";
                break;
            case '\n':
                out << "\\n";
                break;
            case '\r':
                out << "\\r";
                break;
            case '\t':
                out << "\\t";
                break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) {
                    out << "\\u"
                        << std::hex
                        << std::setw(4)
                        << std::setfill('0')
                        << static_cast<int>(static_cast<unsigned char>(ch));
                } else {
                    out << ch;
                }
                break;
        }
    }
    return out.str();
}

DiscordBridge::DiscordBridge()
    : client_(nullptr), ready_(false), authorized_(false), appId_(0) {
    LOGI("DiscordBridge constructed");
}

DiscordBridge::~DiscordBridge() {
    LOGI("DiscordBridge destructor");
    Destroy();
}

bool DiscordBridge::Init(int64_t appId) {
    LOGI("Init called with appId=%lld", (long long)appId);
    std::lock_guard<std::mutex> lock(mutex_);
    if (client_) {
        LOGI("Init: client already exists, destroying first");
        Destroy();
    }

    appId_ = appId;
    try {
        client_ = new discordpp::Client();
        LOGI("Init: Client created, setting appId and callback");
        client_->SetApplicationId(static_cast<uint64_t>(appId));
        client_->SetStatusChangedCallback(
            [this](discordpp::Client::Status status,
                   discordpp::Client::Error error,
                   int32_t errorDetail) {
                std::lock_guard<std::mutex> lock(mutex_);
                LOGI("StatusChanged: status=%d err=%d detail=%d",
                     static_cast<int>(status), static_cast<int>(error), errorDetail);
                if (status == discordpp::Client::Status::Ready) {
                    ready_ = true;
                    LOGI("STATUS: Ready!");
                } else if (status == discordpp::Client::Status::Disconnected) {
                    ready_ = false;
                    LOGI("STATUS: Disconnected (err=%d)", static_cast<int>(error));
                    if (error != discordpp::Client::Error::None) {
                        NotifyJavaError(
                            std::string("connection disconnected, error=") +
                            std::to_string(static_cast<int>(error)) +
                            ", detail=" +
                            std::to_string(errorDetail)
                        );
                    }
                } else if (status == discordpp::Client::Status::Connecting) {
                    LOGI("STATUS: Connecting...");
                } else {
                    LOGI("STATUS: Other status=%d", static_cast<int>(status));
                }
            });
        LOGI("Init: success");
        return true;
    } catch (const std::exception& e) {
        LOGE("Init failed with exception: %s", e.what());
        client_ = nullptr;
        return false;
    } catch (...) {
        LOGE("Init failed with unknown exception");
        client_ = nullptr;
        return false;
    }
}

void DiscordBridge::Authorize() {
    LOGI("Authorize called (client_=%s, authorized_=%s)",
         client_ ? "exists" : "null",
         authorized_ ? "true" : "false");
    std::lock_guard<std::mutex> lock(mutex_);
    if (!client_) {
        LOGE("Authorize: no client, aborting");
        return;
    }
    if (authorized_) {
        LOGW("Authorize: already authorized, aborting");
        return;
    }

    try {
        authorized_ = false;
        ready_ = false;

        LOGI("Authorize: creating code verifier");
        auto verifier = client_->CreateAuthorizationCodeVerifier();
        LOGI("Authorize: code verifier created");

        discordpp::AuthorizationArgs args;
        args.SetClientId(static_cast<uint64_t>(appId_));
        auto scopes = client_->GetDefaultPresenceScopes();
        if (scopes.find("identify") == std::string::npos) {
            scopes += " identify";
        }
        LOGI("Authorize: scopes=%s", scopes.c_str());
        args.SetScopes(scopes);

        discordpp::AuthorizationCodeChallenge challenge;
        challenge.SetChallenge(verifier.Challenge().Challenge());
        challenge.SetMethod(discordpp::AuthenticationCodeChallengeMethod::S256);
        args.SetCodeChallenge(challenge);

        LOGI("Authorize: calling client_->Authorize()...");
        client_->Authorize(
            std::move(args),
            [this, ver = std::move(verifier)](
                discordpp::ClientResult result,
                std::string code,
                std::string redirectUri
            ) mutable {
                if (!result.Successful()) {
                    LOGE("Authorize callback FAILED: err=%s errCode=%d retryable=%s",
                         result.Error().c_str(),
                         result.ErrorCode(),
                         result.Retryable() ? "true" : "false");
                    NotifyJavaError(
                        std::string("authorization failed, code=") +
                        std::to_string(result.ErrorCode()) +
                        ", error=" +
                        result.Error()
                    );
                    return;
                }
                LOGI("Authorize callback SUCCEEDED");
                LOGI("Authorize: exchanging code for token...");
                DoGetToken(std::move(code), std::move(redirectUri), ver.Verifier());
            }
        );
        LOGI("Authorize: client_->Authorize() returned (async flow started)");
    } catch (const std::exception& e) {
        LOGE("Authorize threw exception: %s", e.what());
    } catch (...) {
        LOGE("Authorize threw unknown exception");
    }
}

void DiscordBridge::DoGetToken(
    std::string code, std::string redirectUri, std::string codeVerifier
) {
    LOGI("DoGetToken: exchanging authorization code");
    if (!client_) {
        LOGE("DoGetToken: no client");
        return;
    }
    try {
        LOGI("DoGetToken: calling client_->GetToken()...");
        client_->GetToken(
            static_cast<uint64_t>(appId_),
            code,
            codeVerifier,
            redirectUri,
            [this](discordpp::ClientResult result,
                   std::string accessToken,
                   std::string refreshToken,
                   discordpp::AuthorizationTokenType tokenType,
                   int32_t expiresIn,
                   std::string scopes) {
                if (!result.Successful()) {
                    LOGE("GetToken FAILED: err=%s errCode=%d",
                         result.Error().c_str(), result.ErrorCode());
                    NotifyJavaError(
                        std::string("token exchange failed, code=") +
                        std::to_string(result.ErrorCode()) +
                        ", error=" +
                        result.Error()
                    );
                    return;
                }
                LOGI("GetToken SUCCEEDED: tokenType=%d expiresIn=%d scopes=%s",
                     static_cast<int>(tokenType), expiresIn, scopes.c_str());
                LOGI("GetToken: calling UpdateToken...");
                client_->UpdateToken(
                    tokenType, accessToken,
                    [this, accessToken](discordpp::ClientResult r) {
                        if (!r.Successful()) {
                            LOGE("UpdateToken FAILED: err=%s errCode=%d",
                                 r.Error().c_str(), r.ErrorCode());
                            return;
                        }
                        authorized_ = true;
                        NotifyJavaAuthorized(accessToken);
                        LOGI("UpdateToken SUCCEEDED, calling Connect...");
                        client_->Connect();
                        LOGI("Connect called");
                    }
                );
            }
        );
    } catch (const std::exception& e) {
        LOGE("DoGetToken threw exception: %s", e.what());
    } catch (...) {
        LOGE("DoGetToken threw unknown exception");
    }
}

void DiscordBridge::SetListening(
    const char* name, const char* type, const char* state, const char* details,
    int64_t startSecs, int64_t endSecs,
    const char* largeImage, const char* largeText,
    const char* smallImage, const char* smallText,
    const char* button1Label, const char* button1Url,
    const char* button2Label, const char* button2Url
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!client_) { LOGW("SetListening: no client"); return; }
    if (!ready_) { LOGW("SetListening: not ready"); return; }
    LOGI("SetListening: name=%s state=%s details=%s", name ? name : "null", state ? state : "null", details ? details : "null");

    try {
        discordpp::Activity activity;
        discordpp::ActivityTypes activityType = discordpp::ActivityTypes::Listening;
        if (type) {
            std::string rawType(type);
            std::transform(
                rawType.begin(),
                rawType.end(),
                rawType.begin(),
                [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); }
            );
            if (rawType == "watching") {
                activityType = discordpp::ActivityTypes::Watching;
            } else if (rawType == "competing") {
                activityType = discordpp::ActivityTypes::Competing;
            }
        }
        activity.SetType(activityType);
        activity.SetStatusDisplayType(discordpp::StatusDisplayTypes::Details);
        if (name) activity.SetName(std::string(name));
        if (state) activity.SetState(std::string(state));
        if (details) activity.SetDetails(std::string(details));

        discordpp::ActivityTimestamps ts;
        ts.SetStart(static_cast<uint64_t>(startSecs));
        if (endSecs > 0) ts.SetEnd(static_cast<uint64_t>(endSecs));
        activity.SetTimestamps(std::move(ts));

        discordpp::ActivityAssets assets;
        if (largeImage) assets.SetLargeImage(std::string(largeImage));
        if (largeText) assets.SetLargeText(std::string(largeText));
        if (smallImage) assets.SetSmallImage(std::string(smallImage));
        if (smallText) assets.SetSmallText(std::string(smallText));
        activity.SetAssets(std::move(assets));

        if (button1Label && button1Url && strlen(button1Label) > 0 && strlen(button1Url) > 0) {
            discordpp::ActivityButton btn1;
            btn1.SetLabel(std::string(button1Label));
            btn1.SetUrl(std::string(button1Url));
            activity.AddButton(std::move(btn1));
        }
        if (button2Label && button2Url && strlen(button2Label) > 0 && strlen(button2Url) > 0) {
            discordpp::ActivityButton btn2;
            btn2.SetLabel(std::string(button2Label));
            btn2.SetUrl(std::string(button2Url));
            activity.AddButton(std::move(btn2));
        }

        client_->UpdateRichPresence(
            std::move(activity),
            [](discordpp::ClientResult r) {
                if (!r.Successful()) {
                    NotifyJavaError(
                        std::string("presence update failed, code=") +
                        std::to_string(r.ErrorCode()) +
                        ", error=" +
                        r.Error()
                    );
                    LOGE("UpdateRichPresence failed: err=%s errCode=%d",
                         r.Error().c_str(), r.ErrorCode());
                } else {
                    LOGI("UpdateRichPresence succeeded");
                }
            }
        );
    } catch (const std::exception& e) {
        LOGE("SetListening threw: %s", e.what());
        NotifyJavaError(std::string("presence update threw: ") + e.what());
    }
}

void DiscordBridge::Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!client_) return;
    if (!ready_) return;
    LOGI("Clear called");
    try {
        discordpp::Activity activity;
        client_->UpdateRichPresence(
            std::move(activity),
            [](discordpp::ClientResult r) {
                if (!r.Successful()) {
                    NotifyJavaError(
                        std::string("clear presence failed, code=") +
                        std::to_string(r.ErrorCode()) +
                        ", error=" +
                        r.Error()
                    );
                    LOGE("Clear failed: err=%s", r.Error().c_str());
                } else {
                    LOGI("Clear succeeded");
                }
            }
        );
    } catch (const std::exception& e) {
        LOGE("Clear threw: %s", e.what());
    }
}

void DiscordBridge::Shutdown() {
    LOGI("Shutdown called");
    std::lock_guard<std::mutex> lock(mutex_);
    if (!client_) return;
    try {
        client_->Disconnect();
        LOGI("Shutdown: Disconnect called");
    } catch (const std::exception& e) {
        LOGE("Shutdown threw: %s", e.what());
    }
    ready_ = false;
    authorized_ = false;
    LOGI("Shutdown complete");
}

void DiscordBridge::SetTokenAndConnect(const char* token) {
    LOGI("SetTokenAndConnect: token=%s", token ? "provided" : "null");
    if (!client_) { LOGE("SetTokenAndConnect: no client"); return; }
    if (!token) { LOGE("SetTokenAndConnect: null token"); return; }
    try {
        ready_ = false;
        LOGI("SetTokenAndConnect: creating UpdateToken callback");
        client_->UpdateToken(
            discordpp::AuthorizationTokenType::Bearer,
            std::string(token),
            [this](discordpp::ClientResult result) {
                if (result.Successful()) {
                    authorized_ = true;
                    LOGI("SetTokenAndConnect: UpdateToken succeeded, calling Connect");
                    if (client_) {
                        client_->Connect();
                    }
                } else {
                    LOGE("SetTokenAndConnect: UpdateToken failed: err=%s errCode=%d",
                         result.Error().c_str(), result.ErrorCode());
                    NotifyJavaError(
                        std::string("token update failed, code=") +
                        std::to_string(result.ErrorCode()) +
                        ", error=" +
                        result.Error()
                    );
                    ready_ = false;
                    authorized_ = false;
                }
            }
        );
        LOGI("SetTokenAndConnect: UpdateToken initiated");
    } catch (const std::exception& e) {
        LOGE("SetTokenAndConnect threw: %s", e.what());
        NotifyJavaError(std::string("token update threw: ") + e.what());
    }
}

void DiscordBridge::Connect() {
    LOGI("Connect called");
    if (!client_) { LOGE("Connect: no client"); return; }
    try {
        client_->Connect();
        LOGI("Connect: initiated");
    } catch (const std::exception& e) {
        LOGE("Connect threw: %s", e.what());
        NotifyJavaError(std::string("connect threw: ") + e.what());
    }
}

void DiscordBridge::RunCallbacks() {
    try {
        discordpp::RunCallbacks();
    } catch (const std::exception& e) {
        LOGE("RunCallbacks threw: %s", e.what());
    }
}

std::string DiscordBridge::CurrentUserJson() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!client_) return "";

    try {
        auto userOpt = client_->GetCurrentUserV2();
        if (!userOpt.has_value()) return "";

        auto user = std::move(userOpt.value());
        const auto id = user.Id();
        if (id == 0) return "";

        const auto username = user.Username();
        const auto displayName = user.DisplayName();
        const auto globalName = user.GlobalName().value_or("");
        const auto avatarUrl =
            user.AvatarUrl(
                discordpp::UserHandle::AvatarType::Gif,
                discordpp::UserHandle::AvatarType::Png
            );
        const auto name =
            !displayName.empty()
                ? displayName
                : (!globalName.empty() ? globalName : username);

        std::ostringstream json;
        json << "{"
             << "\"id\":\"" << id << "\","
             << "\"username\":\"" << JsonEscape(username) << "\","
             << "\"name\":\"" << JsonEscape(name) << "\","
             << "\"globalName\":\"" << JsonEscape(globalName) << "\","
             << "\"avatar\":\"" << JsonEscape(avatarUrl) << "\""
             << "}";
        return json.str();
    } catch (const std::exception& e) {
        LOGW("CurrentUserJson threw: %s", e.what());
        return "";
    } catch (...) {
        LOGW("CurrentUserJson threw unknown exception");
        return "";
    }
}

void DiscordBridge::Destroy() {
    LOGI("Destroy called");
    std::lock_guard<std::mutex> lock(mutex_);
    ready_ = false;
    authorized_ = false;
    if (client_) {
        try {
            client_->Disconnect();
            LOGI("Destroy: Disconnected");
        } catch (...) {
            LOGW("Destroy: Disconnect threw (ignored)");
        }
        delete client_;
        client_ = nullptr;
        LOGI("Destroy: client deleted");
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeInit(
    JNIEnv* env, jobject thiz, jlong appId
) {
    env->GetJavaVM(&g_javaVm);
    if (g_managerClass) {
        env->DeleteGlobalRef(g_managerClass);
        g_managerClass = nullptr;
    }
    g_managerClass = reinterpret_cast<jclass>(env->NewGlobalRef(env->GetObjectClass(thiz)));
    return g_discordBridge.Init(static_cast<int64_t>(appId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeIsAuthorized(
    JNIEnv* env, jobject thiz
) {
    return g_discordBridge.IsAuthorized() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeIsReady(
    JNIEnv* env, jobject thiz
) {
    return g_discordBridge.IsReady() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeAuthorize(
    JNIEnv* env, jobject thiz
) {
    g_discordBridge.Authorize();
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeDisconnect(
    JNIEnv* env, jobject thiz
) {
    g_discordBridge.Shutdown();
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeSetTokenAndConnect(
    JNIEnv* env, jobject thiz, jstring token
) {
    const char* tokenStr = token ? env->GetStringUTFChars(token, nullptr) : nullptr;
    if (tokenStr) {
        g_discordBridge.SetTokenAndConnect(tokenStr);
        env->ReleaseStringUTFChars(token, tokenStr);
    }
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeConnect(
    JNIEnv* env, jobject thiz
) {
    g_discordBridge.Connect();
}

JNIEXPORT jstring JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeCurrentUserJson(
    JNIEnv* env, jobject thiz
) {
    const auto json = g_discordBridge.CurrentUserJson();
    if (json.empty()) return nullptr;
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeSetListening(
    JNIEnv* env, jobject thiz,
    jstring name, jstring type, jstring state, jstring details,
    jlong startSecs, jlong endSecs,
    jstring largeImage, jstring largeText,
    jstring smallImage, jstring smallText,
    jstring button1Label, jstring button1Url,
    jstring button2Label, jstring button2Url
) {
    const char* cName = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
    const char* cType = type ? env->GetStringUTFChars(type, nullptr) : nullptr;
    const char* cState = state ? env->GetStringUTFChars(state, nullptr) : nullptr;
    const char* cDetails = details ? env->GetStringUTFChars(details, nullptr) : nullptr;
    const char* cLargeImage = largeImage ? env->GetStringUTFChars(largeImage, nullptr) : nullptr;
    const char* cLargeText = largeText ? env->GetStringUTFChars(largeText, nullptr) : nullptr;
    const char* cSmallImage = smallImage ? env->GetStringUTFChars(smallImage, nullptr) : nullptr;
    const char* cSmallText = smallText ? env->GetStringUTFChars(smallText, nullptr) : nullptr;
    const char* cBtn1Label = button1Label ? env->GetStringUTFChars(button1Label, nullptr) : nullptr;
    const char* cBtn1Url = button1Url ? env->GetStringUTFChars(button1Url, nullptr) : nullptr;
    const char* cBtn2Label = button2Label ? env->GetStringUTFChars(button2Label, nullptr) : nullptr;
    const char* cBtn2Url = button2Url ? env->GetStringUTFChars(button2Url, nullptr) : nullptr;

    g_discordBridge.SetListening(
        cName, cType, cState, cDetails,
        static_cast<int64_t>(startSecs), static_cast<int64_t>(endSecs),
        cLargeImage, cLargeText, cSmallImage, cSmallText,
        cBtn1Label, cBtn1Url, cBtn2Label, cBtn2Url
    );

    if (cName) env->ReleaseStringUTFChars(name, cName);
    if (cType) env->ReleaseStringUTFChars(type, cType);
    if (cState) env->ReleaseStringUTFChars(state, cState);
    if (cDetails) env->ReleaseStringUTFChars(details, cDetails);
    if (cLargeImage) env->ReleaseStringUTFChars(largeImage, cLargeImage);
    if (cLargeText) env->ReleaseStringUTFChars(largeText, cLargeText);
    if (cSmallImage) env->ReleaseStringUTFChars(smallImage, cSmallImage);
    if (cSmallText) env->ReleaseStringUTFChars(smallText, cSmallText);
    if (cBtn1Label) env->ReleaseStringUTFChars(button1Label, cBtn1Label);
    if (cBtn1Url) env->ReleaseStringUTFChars(button1Url, cBtn1Url);
    if (cBtn2Label) env->ReleaseStringUTFChars(button2Label, cBtn2Label);
    if (cBtn2Url) env->ReleaseStringUTFChars(button2Url, cBtn2Url);
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeClear(
    JNIEnv* env, jobject thiz
) {
    g_discordBridge.Clear();
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeRunCallbacks(
    JNIEnv* env, jobject thiz
) {
    g_discordBridge.RunCallbacks();
}

JNIEXPORT void JNICALL
Java_com_metrolist_music_discord_DiscordRpcManager_nativeDestroy(
    JNIEnv* env, jobject thiz
) {
    g_discordBridge.Destroy();
}

} // extern "C"
