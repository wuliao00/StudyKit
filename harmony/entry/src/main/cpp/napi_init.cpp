#include <napi/native_api.h>

#include <hilog/log.h>

#include <exception>
#include <string>
#include <vector>

#include "ppocr_engine.h"

#undef LOG_DOMAIN
#undef LOG_TAG
#define LOG_DOMAIN 0x3201
#define LOG_TAG "PPOCR"

namespace {

std::string JsonEscape(const std::string& s) {
  std::string out;
  out.reserve(s.size() + 8);
  for (unsigned char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else {
          out += (char)c;
        }
    }
  }
  return out;
}

napi_value MakeString(napi_env env, const std::string& s) {
  napi_value out = nullptr;
  napi_create_string_utf8(env, s.c_str(), s.size(), &out);
  return out;
}

napi_value ErrorJson(napi_env env, const std::string& message) {
  return MakeString(env, "{\"ok\":false,\"error\":\"" + JsonEscape(message) + "\"}");
}

std::string GetStringArg(napi_env env, napi_value value) {
  size_t len = 0;
  napi_get_value_string_utf8(env, value, nullptr, 0, &len);
  std::vector<char> buf(len + 1, 0);
  napi_get_value_string_utf8(env, value, buf.data(), len + 1, &len);
  return std::string(buf.data(), len);
}

bool GetInt64Arg(napi_env env, napi_value value, int64_t* out) {
  napi_valuetype type = napi_undefined;
  napi_typeof(env, value, &type);
  if (type != napi_number) return false;
  return napi_get_value_int64(env, value, out) == napi_ok;
}

bool GetInt32Arg(napi_env env, napi_value value, int32_t* out) {
  napi_valuetype type = napi_undefined;
  napi_typeof(env, value, &type);
  if (type != napi_number) return false;
  return napi_get_value_int32(env, value, out) == napi_ok;
}

napi_value Version(napi_env env, napi_callback_info info) {
  return MakeString(env, PpOcrEngine::Get().VersionJson());
}

napi_value InitEngine(napi_env env, napi_callback_info info) {
  size_t argc = 7;
  napi_value argv[7] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 7) {
    return ErrorJson(env, "init requires 7 args: detFd,detOffset,detLen,recFd,recOffset,recLen,dict");
  }
  int64_t detFd = 0;
  int64_t detOffset = 0;
  int64_t detLen = 0;
  int64_t recFd = 0;
  int64_t recOffset = 0;
  int64_t recLen = 0;
  if (!GetInt64Arg(env, argv[0], &detFd) || !GetInt64Arg(env, argv[1], &detOffset) ||
      !GetInt64Arg(env, argv[2], &detLen) || !GetInt64Arg(env, argv[3], &recFd) ||
      !GetInt64Arg(env, argv[4], &recOffset) || !GetInt64Arg(env, argv[5], &recLen)) {
    return ErrorJson(env, "init: fd/offset/length must be numbers");
  }
  std::string dict = GetStringArg(env, argv[6]);
  std::string infoJson;
  std::string error;
  try {
    if (!PpOcrEngine::Get().Init((int)detFd, detOffset, detLen, (int)recFd, recOffset, recLen, dict,
                                 &infoJson, &error)) {
      OH_LOG_ERROR(LOG_APP, "PPOCR init failed: %{public}s", error.c_str());
      return ErrorJson(env, error);
    }
  } catch (const std::exception& e) {
    return ErrorJson(env, std::string("init exception: ") + e.what());
  } catch (...) {
    return ErrorJson(env, "init unknown exception");
  }
  OH_LOG_INFO(LOG_APP, "PPOCR init ok: %{public}s", infoJson.c_str());
  return MakeString(env, infoJson);
}

napi_value Probe(napi_env env, napi_callback_info info) {
  std::string error;
  try {
    std::string json = PpOcrEngine::Get().ProbeJson(&error);
    if (json.empty()) return ErrorJson(env, error);
    OH_LOG_INFO(LOG_APP, "PPOCR probe: %{public}s", json.c_str());
    return MakeString(env, json);
  } catch (const std::exception& e) {
    return ErrorJson(env, std::string("probe exception: ") + e.what());
  }
}

napi_value RecognizeSync(napi_env env, napi_callback_info info) {
  size_t argc = 4;
  napi_value argv[4] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 3) {
    return ErrorJson(env, "recognizeSync requires: rgbaBuffer,width,height[,linePass]");
  }
  bool isBuffer = false;
  napi_is_arraybuffer(env, argv[0], &isBuffer);
  if (!isBuffer) return ErrorJson(env, "pixels must be an ArrayBuffer");
  void* data = nullptr;
  size_t len = 0;
  napi_get_arraybuffer_info(env, argv[0], &data, &len);
  int32_t width = 0;
  int32_t height = 0;
  if (!GetInt32Arg(env, argv[1], &width) || !GetInt32Arg(env, argv[2], &height) || width <= 0 ||
      height <= 0) {
    return ErrorJson(env, "width/height invalid");
  }
  bool linePass = true;
  if (argc >= 4) napi_get_value_bool(env, argv[3], &linePass);
  if (len < (size_t)width * (size_t)height * 4) {
    return ErrorJson(env, "pixels buffer smaller than width*height*4");
  }
  std::string error;
  try {
    std::string json = PpOcrEngine::Get().RecognizeJson((const uint8_t*)data, width, height, linePass,
                                                        &error);
    if (json.empty()) return ErrorJson(env, error);
    OH_LOG_INFO(LOG_APP, "PPOCR recognizeSync %{public}s", json.substr(0, 200).c_str());
    return MakeString(env, json);
  } catch (const std::exception& e) {
    return ErrorJson(env, std::string("recognize exception: ") + e.what());
  }
}

struct AsyncWork {
  napi_env env = nullptr;
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  std::vector<uint8_t> rgba;
  int32_t width = 0;
  int32_t height = 0;
  bool linePass = true;
  std::string result;
  std::string error;
  bool ok = false;
};

void ExecuteWork(napi_env env, void* data) {
  AsyncWork* work = static_cast<AsyncWork*>(data);
  try {
    std::string error;
    work->result = PpOcrEngine::Get().RecognizeJson(work->rgba.data(), work->width, work->height,
                                                    work->linePass, &error);
    work->ok = !work->result.empty();
    if (!work->ok) work->error = error;
  } catch (const std::exception& e) {
    work->ok = false;
    work->error = std::string("recognize exception: ") + e.what();
  } catch (...) {
    work->ok = false;
    work->error = "recognize unknown exception";
  }
}

void CompleteWork(napi_env env, napi_status status, void* data) {
  AsyncWork* work = static_cast<AsyncWork*>(data);
  std::string json =
      work->ok ? work->result : "{\"ok\":false,\"error\":\"" + JsonEscape(work->error) + "\"}";
  OH_LOG_INFO(LOG_APP, "PPOCR recognize done %{public}s", json.substr(0, 200).c_str());
  napi_value value = MakeString(env, json);
  if (status == napi_ok) {
    napi_resolve_deferred(env, work->deferred, value);
  } else {
    napi_value error = nullptr;
    napi_create_error(env, nullptr, value, &error);
    napi_reject_deferred(env, work->deferred, error);
  }
  napi_delete_async_work(env, work->work);
  delete work;
}

napi_value RecognizeAsync(napi_env env, napi_callback_info info) {
  size_t argc = 4;
  napi_value argv[4] = {nullptr};
  napi_get_cb_info(env, info, &argc, argv, nullptr, nullptr);
  if (argc < 3) {
    return ErrorJson(env, "recognize requires: rgbaBuffer,width,height[,linePass]");
  }
  bool isBuffer = false;
  napi_is_arraybuffer(env, argv[0], &isBuffer);
  if (!isBuffer) return ErrorJson(env, "pixels must be an ArrayBuffer");
  void* data = nullptr;
  size_t len = 0;
  napi_get_arraybuffer_info(env, argv[0], &data, &len);
  int32_t width = 0;
  int32_t height = 0;
  if (!GetInt32Arg(env, argv[1], &width) || !GetInt32Arg(env, argv[2], &height) || width <= 0 ||
      height <= 0) {
    return ErrorJson(env, "width/height invalid");
  }
  bool linePass = true;
  if (argc >= 4) napi_get_value_bool(env, argv[3], &linePass);
  if (len < (size_t)width * (size_t)height * 4) {
    return ErrorJson(env, "pixels buffer smaller than width*height*4");
  }
  OH_LOG_INFO(LOG_APP, "PPOCR recognize request %{public}dx%{public}d bytes=%{public}d linePass=%{public}d",
              width, height, (int)len, linePass ? 1 : 0);
  napi_deferred deferred = nullptr;
  napi_value promise = nullptr;
  if (napi_create_promise(env, &deferred, &promise) != napi_ok) {
    return ErrorJson(env, "create promise failed");
  }
  AsyncWork* work = nullptr;
  try {
    work = new AsyncWork();
    work->env = env;
    work->deferred = deferred;
    work->width = width;
    work->height = height;
    work->linePass = linePass;
    work->rgba.assign((uint8_t*)data, (uint8_t*)data + (size_t)width * height * 4);
  } catch (const std::exception& e) {
    delete work;
    OH_LOG_ERROR(LOG_APP, "PPOCR recognize buffer allocation failed: %{public}s", e.what());
    return ErrorJson(env, std::string("native buffer allocation failed: ") + e.what());
  } catch (...) {
    delete work;
    OH_LOG_ERROR(LOG_APP, "PPOCR recognize buffer allocation failed: unknown");
    return ErrorJson(env, "native buffer allocation failed");
  }
  napi_value name = nullptr;
  napi_create_string_utf8(env, "ppocrRecognize", NAPI_AUTO_LENGTH, &name);
  if (napi_create_async_work(env, nullptr, name, ExecuteWork, CompleteWork, work, &work->work) !=
      napi_ok) {
    delete work;
    napi_value value = ErrorJson(env, "create async work failed");
    napi_resolve_deferred(env, deferred, value);
    return promise;
  }
  if (napi_queue_async_work(env, work->work) != napi_ok) {
    napi_delete_async_work(env, work->work);
    delete work;
    napi_value value = ErrorJson(env, "queue async work failed");
    napi_resolve_deferred(env, deferred, value);
    return promise;
  }
  return promise;
}

napi_value AbiInfo(napi_env env, napi_callback_info info) {
  char buf[128];
  snprintf(buf, sizeof(buf), "{\"pointerSize\":%d,\"napiVersion\":%d}", (int)sizeof(void*),
           NAPI_VERSION);
  return MakeString(env, buf);
}

napi_value ReleaseEngine(napi_env env, napi_callback_info info) {
  return MakeString(env, "{\"ok\":true}");
}

}  // namespace

EXTERN_C_START
static napi_value Init(napi_env env, napi_value exports) {
  napi_property_descriptor desc[] = {
      {"version", nullptr, Version, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"init", nullptr, InitEngine, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"probe", nullptr, Probe, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"recognize", nullptr, RecognizeAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"recognizeSync", nullptr, RecognizeSync, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"abi", nullptr, AbiInfo, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"release", nullptr, ReleaseEngine, nullptr, nullptr, nullptr, napi_default, nullptr},
  };
  napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
  return exports;
}
EXTERN_C_END

static napi_module ppocrModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "ppocr",
    .nm_priv = ((void*)0),
    .reserved = {0},
};

extern "C" __attribute__((constructor, visibility("default"))) void RegisterPpocrModule(void) {
  napi_module_register(&ppocrModule);
}
