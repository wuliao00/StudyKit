#include "ppocr_engine.h"

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

constexpr int DET_LIMIT_SIDE_LEN = 736;
constexpr float DET_THRESH = 0.3f;
constexpr float DET_BOX_THRESH = 0.5f;
constexpr float DET_UNCLIP_RATIO = 1.6f;
constexpr int DET_MIN_SIZE = 3;
constexpr float TEXT_SCORE = 0.5f;
constexpr int REC_IMG_H = 48;
constexpr int REC_IMG_W_REF = 320;
constexpr int MAX_DET_SIDE = 1600;
constexpr float DEFAULT_RETRY_BELOW = 0.72f;
constexpr int DEFAULT_MAX_RETRIES = 8;

long NowMs() {
  static const auto kStart = std::chrono::steady_clock::now();
  return (long)std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now() - kStart)
      .count();
}

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

bool ReadFd(int fd, int64_t offset, int64_t len, std::vector<uint8_t>* out) {
  if (fd < 0 || len <= 0) return false;
  out->resize((size_t)len);
  int64_t got = 0;
  while (got < len) {
    ssize_t n = pread(fd, out->data() + got, (size_t)(len - got), (off_t)(offset + got));
    if (n <= 0) return false;
    got += n;
  }
  return true;
}

// cv2.dilate 等价：kernel 2x2，anchor=(1,1)，输出 (y,x) 取以它右下角的 2x2 最大值。
void Dilate2x2(std::vector<uint8_t>* mask, int w, int h) {
  std::vector<uint8_t> out(mask->size(), 0);
  for (int y = 0; y < h; ++y) {
    for (int x = 0; x < w; ++x) {
      uint8_t v = (*mask)[y * w + x];
      if (!v && y > 0) v = (*mask)[(y - 1) * w + x];
      if (!v && x > 0) v = (*mask)[y * w + (x - 1)];
      if (!v && x > 0 && y > 0) v = (*mask)[(y - 1) * w + (x - 1)];
      out[y * w + x] = v;
    }
  }
  mask->swap(out);
}

void DetResizeDims(int origW, int origH, int* rw, int* rh) {
  double w = (double)origW;
  double h = (double)origH;
  double longSide = std::max(w, h);
  if (longSide > (double)MAX_DET_SIDE) {
    double g = (double)MAX_DET_SIDE / longSide;
    w *= g;
    h *= g;
  }
  double ratio = 1.0;
  double minSide = std::min(w, h);
  if (minSide < (double)DET_LIMIT_SIDE_LEN && minSide > 0) {
    ratio = (double)DET_LIMIT_SIDE_LEN / minSide;
  }
  long rhh = (long)std::llround(h * ratio / 32.0) * 32;
  long rww = (long)std::llround(w * ratio / 32.0) * 32;
  *rh = (int)std::max(32L, rhh);
  *rw = (int)std::max(32L, rww);
}

// RGBA -> 双线性缩放到 dstW x dstH，输出 BGR planar 归一化 float。
void ResizeToBgrPlanar(const uint8_t* src, int sw, int sh, int dw, int dh, float* dst) {
  const float scaleX = (float)sw / (float)dw;
  const float scaleY = (float)sh / (float)dh;
  const size_t plane = (size_t)dw * (size_t)dh;
  for (int y = 0; y < dh; ++y) {
    float fy = ((float)y + 0.5f) * scaleY - 0.5f;
    if (fy < 0.0f) fy = 0.0f;
    if (fy > (float)(sh - 1)) fy = (float)(sh - 1);
    int y0 = (int)std::floor(fy);
    int y1 = std::min(y0 + 1, sh - 1);
    float wy = fy - (float)y0;
    for (int x = 0; x < dw; ++x) {
      float fx = ((float)x + 0.5f) * scaleX - 0.5f;
      if (fx < 0.0f) fx = 0.0f;
      if (fx > (float)(sw - 1)) fx = (float)(sw - 1);
      int x0 = (int)std::floor(fx);
      int x1 = std::min(x0 + 1, sw - 1);
      float wx = fx - (float)x0;
      const uint8_t* p00 = src + ((size_t)y0 * sw + x0) * 4;
      const uint8_t* p01 = src + ((size_t)y0 * sw + x1) * 4;
      const uint8_t* p10 = src + ((size_t)y1 * sw + x0) * 4;
      const uint8_t* p11 = src + ((size_t)y1 * sw + x1) * 4;
      float w00 = (1.0f - wx) * (1.0f - wy);
      float w01 = wx * (1.0f - wy);
      float w10 = (1.0f - wx) * wy;
      float w11 = wx * wy;
      float b = (p00[2] * w00 + p01[2] * w01 + p10[2] * w10 + p11[2] * w11) / 255.0f;
      float g = (p00[1] * w00 + p01[1] * w01 + p10[1] * w10 + p11[1] * w11) / 255.0f;
      float r = (p00[0] * w00 + p01[0] * w01 + p10[0] * w10 + p11[0] * w11) / 255.0f;
      size_t i = (size_t)y * dw + x;
      dst[i] = (b - 0.5f) / 0.5f;
      dst[plane + i] = (g - 0.5f) / 0.5f;
      dst[2 * plane + i] = (r - 0.5f) / 0.5f;
    }
  }
}

// 裁剪 RGBA（坐标已裁剪到图内）。
void CropRgba(const uint8_t* src, int sw, int sh, int x0, int y0, int x1, int y1,
              std::vector<uint8_t>* out, int* outW, int* outH) {
  int w = std::max(1, x1 - x0);
  int h = std::max(1, y1 - y0);
  out->resize((size_t)w * h * 4);
  for (int y = 0; y < h; ++y) {
    memcpy(out->data() + (size_t)y * w * 4,
           src + ((size_t)(y0 + y) * sw + x0) * 4, (size_t)w * 4);
  }
  *outW = w;
  *outH = h;
}

void UpscaleRgba(const std::vector<uint8_t>& src, int sw, int sh, int dw, int dh,
                 std::vector<uint8_t>* out) {
  out->resize((size_t)dw * dh * 4);
  const float scaleX = (float)sw / (float)dw;
  const float scaleY = (float)sh / (float)dh;
  for (int y = 0; y < dh; ++y) {
    float fy = ((float)y + 0.5f) * scaleY - 0.5f;
    if (fy < 0.0f) fy = 0.0f;
    if (fy > (float)(sh - 1)) fy = (float)(sh - 1);
    int y0 = (int)std::floor(fy);
    int y1 = std::min(y0 + 1, sh - 1);
    float wy = fy - (float)y0;
    for (int x = 0; x < dw; ++x) {
      float fx = ((float)x + 0.5f) * scaleX - 0.5f;
      if (fx < 0.0f) fx = 0.0f;
      if (fx > (float)(sw - 1)) fx = (float)(sw - 1);
      int x0 = (int)std::floor(fx);
      int x1 = std::min(x0 + 1, sw - 1);
      float wx = fx - (float)x0;
      const uint8_t* p00 = src.data() + ((size_t)y0 * sw + x0) * 4;
      const uint8_t* p01 = src.data() + ((size_t)y0 * sw + x1) * 4;
      const uint8_t* p10 = src.data() + ((size_t)y1 * sw + x0) * 4;
      const uint8_t* p11 = src.data() + ((size_t)y1 * sw + x1) * 4;
      float w00 = (1.0f - wx) * (1.0f - wy);
      float w01 = wx * (1.0f - wy);
      float w10 = (1.0f - wx) * wy;
      float w11 = wx * wy;
      uint8_t* d = out->data() + ((size_t)y * dw + x) * 4;
      for (int c = 0; c < 4; ++c) {
        d[c] = (uint8_t)std::lround(p00[c] * w00 + p01[c] * w01 + p10[c] * w10 + p11[c] * w11);
      }
    }
  }
}

void SplitDict(const std::string& raw, std::vector<std::string>* chars) {
  chars->clear();
  chars->push_back("");  // index 0 = CTC blank
  size_t start = 0;
  while (start <= raw.size()) {
    size_t pos = raw.find('\n', start);
    std::string line;
    if (pos == std::string::npos) {
      line = raw.substr(start);
      if (!line.empty() && line.back() == '\r') line.pop_back();
      chars->push_back(line);
      break;
    }
    line = raw.substr(start, pos - start);
    if (!line.empty() && line.back() == '\r') line.pop_back();
    chars->push_back(line);
    start = pos + 1;
    if (start == raw.size()) break;
  }
  chars->push_back(" ");  // index N+1 = space
}

// 低置信度二次识别择优（与 Android betterReading 完全一致）。
void BetterReading(const std::string& oldText, float oldScore, const std::string& newText,
                   float newScore, float retryBelow, std::string* outText, float* outScore) {
  if (newText.empty()) {
    *outText = oldText;
    *outScore = oldScore;
    return;
  }
  if (oldText == newText && newScore > oldScore) {
    *outText = oldText;
    *outScore = newScore;
    return;
  }
  if (newScore > oldScore + 0.02f) {
    *outText = newText;
    *outScore = newScore;
    return;
  }
  if (oldScore < retryBelow && newScore > oldScore) {
    *outText = newText;
    *outScore = newScore;
    return;
  }
  *outText = oldText;
  *outScore = oldScore;
}

}  // namespace

PpOcrEngine& PpOcrEngine::Get() {
  static PpOcrEngine engine;
  return engine;
}

PpOcrEngine::~PpOcrEngine() { Release(); }

void PpOcrEngine::Release() {
  if (api_ != nullptr) {
    if (det_session_ != nullptr) api_->ReleaseSession(det_session_);
    if (rec_session_ != nullptr) api_->ReleaseSession(rec_session_);
    if (mem_info_ != nullptr) api_->ReleaseMemoryInfo(mem_info_);
    if (env_ != nullptr) api_->ReleaseEnv(env_);
  }
  det_session_ = nullptr;
  rec_session_ = nullptr;
  mem_info_ = nullptr;
  env_ = nullptr;
  alloc_ = nullptr;
  ready_ = false;
}

bool PpOcrEngine::EnsureOrt(std::string* err) {
  if (api_ != nullptr) return true;
  const OrtApiBase* base = OrtGetApiBase();
  if (base == nullptr) {
    *err = "OrtGetApiBase returned null";
    return false;
  }
  api_ = base->GetApi(ORT_API_VERSION);
  if (api_ == nullptr) {
    *err = std::string("ORT runtime does not support API version ") +
           std::to_string(ORT_API_VERSION) + " (runtime=" + base->GetVersionString() + ")";
    return false;
  }
  OrtStatus* st = api_->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "ppocr", &env_);
  if (st != nullptr) {
    *err = std::string("CreateEnv: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  st = api_->GetAllocatorWithDefaultOptions(&alloc_);
  if (st != nullptr) {
    *err = std::string("GetAllocator: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  st = api_->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem_info_);
  if (st != nullptr) {
    *err = std::string("CreateCpuMemoryInfo: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  return true;
}

bool PpOcrEngine::CreateSession(const std::vector<uint8_t>& model, bool det, std::string* err) {
  OrtSessionOptions* opts = nullptr;
  OrtStatus* st = api_->CreateSessionOptions(&opts);
  if (st != nullptr) {
    *err = std::string("CreateSessionOptions: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  api_->SetIntraOpNumThreads(opts, 4);
  api_->SetInterOpNumThreads(opts, 1);
  api_->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL);
  OrtSession* session = nullptr;
  st = api_->CreateSessionFromArray(env_, model.data(), model.size(), opts, &session);
  if (st != nullptr) {
    *err = std::string(det ? "det " : "rec ") + "CreateSessionFromArray: " + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseSessionOptions(opts);
    return false;
  }
  api_->ReleaseSessionOptions(opts);

  char* name = nullptr;
  st = api_->SessionGetInputName(session, 0, alloc_, &name);
  if (st != nullptr) {
    *err = std::string("SessionGetInputName: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseSession(session);
    return false;
  }
  std::string input = name;
  api_->AllocatorFree(alloc_, name);
  name = nullptr;
  st = api_->SessionGetOutputName(session, 0, alloc_, &name);
  if (st != nullptr) {
    *err = std::string("SessionGetOutputName: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseSession(session);
    return false;
  }
  std::string output = name;
  api_->AllocatorFree(alloc_, name);

  if (det) {
    det_session_ = session;
    det_input_ = input;
    det_output_ = output;
  } else {
    rec_session_ = session;
    rec_input_ = input;
    rec_output_ = output;
  }
  return true;
}

bool PpOcrEngine::Init(int detFd, int64_t detOffset, int64_t detLen,
                       int recFd, int64_t recOffset, int64_t recLen,
                       const std::string& dictText, std::string* infoJson, std::string* err) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (ready_) {
    *infoJson = info_json_;
    return true;
  }
  Release();
  if (!EnsureOrt(err)) return false;
  long t0 = NowMs();
  std::vector<uint8_t> detBytes;
  std::vector<uint8_t> recBytes;
  if (!ReadFd(detFd, detOffset, detLen, &detBytes)) {
    *err = "read det model from fd failed";
    return false;
  }
  long t1 = NowMs();
  if (!CreateSession(detBytes, true, err)) {
    return false;
  }
  detBytes.clear();
  detBytes.shrink_to_fit();
  long t2 = NowMs();
  if (!ReadFd(recFd, recOffset, recLen, &recBytes)) {
    *err = "read rec model from fd failed";
    return false;
  }
  long t3 = NowMs();
  if (!CreateSession(recBytes, false, err)) {
    return false;
  }
  recBytes.clear();
  recBytes.shrink_to_fit();
  long t4 = NowMs();
  SplitDict(dictText, &chars_);
  ready_ = true;
  char buf[512];
  snprintf(buf, sizeof(buf),
           "{\"ok\":true,\"ortVersion\":\"%s\",\"apiVersion\":%d,\"detMs\":%ld,\"recMs\":%ld,"
           "\"detIn\":\"%s\",\"detOut\":\"%s\",\"recIn\":\"%s\",\"recOut\":\"%s\",\"dictSize\":%d,"
           "\"classes\":%d}",
           OrtGetApiBase()->GetVersionString(), ORT_API_VERSION, (t1 - t0) + (t2 - t1),
           (t3 - t2) + (t4 - t3), det_input_.c_str(), det_output_.c_str(), rec_input_.c_str(),
           rec_output_.c_str(), (int)chars_.size() - 2, (int)chars_.size());
  info_json_ = buf;
  *infoJson = info_json_;
  return true;
}

std::string PpOcrEngine::VersionJson() const {
  char buf[256];
  snprintf(buf, sizeof(buf), "{\"ortVersion\":\"%s\",\"apiVersion\":%d,\"ready\":%s}",
           OrtGetApiBase()->GetVersionString(), ORT_API_VERSION, ready_ ? "true" : "false");
  return std::string(buf);
}

bool PpOcrEngine::RunDetTensor(const float* data, int rw, int rh, std::vector<float>* out,
                               std::string* err) {
  int64_t dims[4] = {1, 3, (int64_t)rh, (int64_t)rw};
  OrtValue* input = nullptr;
  OrtStatus* st = api_->CreateTensorWithDataAsOrtValue(
      mem_info_, (void*)data, (size_t)3 * rw * rh * sizeof(float), dims, 4,
      ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input);
  if (st != nullptr) {
    *err = std::string("det CreateTensor: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  const char* inNames[1] = {det_input_.c_str()};
  const char* outNames[1] = {det_output_.c_str()};
  OrtValue* output = nullptr;
  st = api_->Run(det_session_, nullptr, inNames, &input, 1, outNames, 1, &output);
  api_->ReleaseValue(input);
  if (st != nullptr) {
    *err = std::string("det Run: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  OrtTensorTypeAndShapeInfo* detShape = nullptr;
  st = api_->GetTensorTypeAndShape(output, &detShape);
  if (st != nullptr) {
    *err = std::string("det GetTensorTypeAndShape: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseValue(output);
    return false;
  }
  size_t elemCount = 0;
  st = api_->GetTensorShapeElementCount(detShape, &elemCount);
  api_->ReleaseTensorTypeAndShapeInfo(detShape);
  if (st != nullptr) {
    *err = std::string("det GetTensorShapeElementCount: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseValue(output);
    return false;
  }
  if (elemCount != (size_t)rw * (size_t)rh) {
    char buf[160];
    snprintf(buf, sizeof(buf), "det output element count %zu != %d*%d", elemCount, rw, rh);
    *err = buf;
    api_->ReleaseValue(output);
    return false;
  }
  float* outData = nullptr;
  st = api_->GetTensorMutableData(output, (void**)&outData);
  if (st != nullptr) {
    *err = std::string("det GetTensorMutableData: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseValue(output);
    return false;
  }
  out->assign(outData, outData + (size_t)rw * rh);
  api_->ReleaseValue(output);
  return true;
}

bool PpOcrEngine::RunRecTensor(const float* data, int imgW, std::string* text, float* score,
                               std::string* err) {
  int64_t dims[4] = {1, 3, (int64_t)REC_IMG_H, (int64_t)imgW};
  OrtValue* input = nullptr;
  OrtStatus* st = api_->CreateTensorWithDataAsOrtValue(
      mem_info_, (void*)data, (size_t)3 * REC_IMG_H * imgW * sizeof(float), dims, 4,
      ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input);
  if (st != nullptr) {
    *err = std::string("rec CreateTensor: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  const char* inNames[1] = {rec_input_.c_str()};
  const char* outNames[1] = {rec_output_.c_str()};
  OrtValue* output = nullptr;
  st = api_->Run(rec_session_, nullptr, inNames, &input, 1, outNames, 1, &output);
  api_->ReleaseValue(input);
  if (st != nullptr) {
    *err = std::string("rec Run: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    return false;
  }
  OrtTensorTypeAndShapeInfo* shape = nullptr;
  st = api_->GetTensorTypeAndShape(output, &shape);
  if (st != nullptr) {
    *err = std::string("rec GetTensorTypeAndShape: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseValue(output);
    return false;
  }
  size_t rank = 0;
  api_->GetDimensionsCount(shape, &rank);
  int64_t odims[4] = {0, 0, 0, 0};
  if (rank > 4) rank = 4;
  api_->GetDimensions(shape, odims, rank);
  api_->ReleaseTensorTypeAndShapeInfo(shape);
  if (rank != 3 || odims[2] <= 0) {
    *err = "rec unexpected output rank";
    api_->ReleaseValue(output);
    return false;
  }
  float* outData = nullptr;
  st = api_->GetTensorMutableData(output, (void**)&outData);
  if (st != nullptr) {
    *err = std::string("rec GetTensorMutableData: ") + api_->GetErrorMessage(st);
    api_->ReleaseStatus(st);
    api_->ReleaseValue(output);
    return false;
  }
  const int frames = (int)odims[1];
  const int classes = (int)odims[2];
  std::string out;
  double confSum = 0.0;
  int confCnt = 0;
  int prev = -1;
  for (int t = 0; t < frames; ++t) {
    const float* probs = outData + (size_t)t * classes;
    int best = 0;
    float bestV = probs[0];
    for (int i = 1; i < classes; ++i) {
      if (probs[i] > bestV) {
        bestV = probs[i];
        best = i;
      }
    }
    if (best != prev && best != 0 && best < (int)chars_.size()) {
      out += chars_[best];
      confSum += bestV;
      ++confCnt;
    }
    prev = best;
  }
  api_->ReleaseValue(output);
  *text = out;
  *score = confCnt == 0 ? 0.0f : (float)(confSum / confCnt);
  return true;
}

bool PpOcrEngine::RunRec(const uint8_t* crop, int w, int h, std::string* text, float* score,
                         std::string* err) {
  const double ratio = (double)w / (double)h;
  const double maxWhRatio = std::max((double)REC_IMG_W_REF / (double)REC_IMG_H, ratio);
  int imgW = std::max(4, (int)((double)REC_IMG_H * maxWhRatio));
  int resizedW = (int)std::ceil((double)REC_IMG_H * ratio);
  if (resizedW > imgW) resizedW = imgW;
  if (resizedW < 1) resizedW = 1;
  std::vector<float> tensor((size_t)3 * REC_IMG_H * imgW, 0.0f);
  // 只填左侧 resizedW 列，其余保留 0（与 Android 右侧补 0 一致）。
  const size_t plane = (size_t)REC_IMG_H * imgW;
  const float scaleX = (float)w / (float)resizedW;
  const float scaleY = (float)h / (float)REC_IMG_H;
  for (int y = 0; y < REC_IMG_H; ++y) {
    float fy = ((float)y + 0.5f) * scaleY - 0.5f;
    if (fy < 0.0f) fy = 0.0f;
    if (fy > (float)(h - 1)) fy = (float)(h - 1);
    int y0 = (int)std::floor(fy);
    int y1 = std::min(y0 + 1, h - 1);
    float wy = fy - (float)y0;
    for (int x = 0; x < resizedW; ++x) {
      float fx = ((float)x + 0.5f) * scaleX - 0.5f;
      if (fx < 0.0f) fx = 0.0f;
      if (fx > (float)(w - 1)) fx = (float)(w - 1);
      int x0 = (int)std::floor(fx);
      int x1 = std::min(x0 + 1, w - 1);
      float wx = fx - (float)x0;
      const uint8_t* p00 = crop + ((size_t)y0 * w + x0) * 4;
      const uint8_t* p01 = crop + ((size_t)y0 * w + x1) * 4;
      const uint8_t* p10 = crop + ((size_t)y1 * w + x0) * 4;
      const uint8_t* p11 = crop + ((size_t)y1 * w + x1) * 4;
      float w00 = (1.0f - wx) * (1.0f - wy);
      float w01 = wx * (1.0f - wy);
      float w10 = (1.0f - wx) * wy;
      float w11 = wx * wy;
      float b = (p00[2] * w00 + p01[2] * w01 + p10[2] * w10 + p11[2] * w11) / 255.0f;
      float g = (p00[1] * w00 + p01[1] * w01 + p10[1] * w10 + p11[1] * w11) / 255.0f;
      float r = (p00[0] * w00 + p01[0] * w01 + p10[0] * w10 + p11[0] * w11) / 255.0f;
      size_t i = (size_t)y * imgW + x;
      tensor[i] = (b - 0.5f) / 0.5f;
      tensor[plane + i] = (g - 0.5f) / 0.5f;
      tensor[2 * plane + i] = (r - 0.5f) / 0.5f;
    }
  }
  return RunRecTensor(tensor.data(), imgW, text, score, err);
}

bool PpOcrEngine::RunDet(const uint8_t* rgba, int w, int h, std::vector<PpLineBox>* boxes,
                         long* detMs, std::string* err) {
  int rw = 0;
  int rh = 0;
  DetResizeDims(w, h, &rw, &rh);
  std::vector<float> tensor((size_t)3 * rw * rh, 0.0f);
  ResizeToBgrPlanar(rgba, w, h, rw, rh, tensor.data());
  std::vector<float> pred;
  long t0 = NowMs();
  if (!RunDetTensor(tensor.data(), rw, rh, &pred, err)) return false;
  *detMs = NowMs() - t0;

  std::vector<uint8_t> mask((size_t)rw * rh, 0);
  for (size_t i = 0; i < mask.size(); ++i) mask[i] = pred[i] > DET_THRESH ? 1 : 0;
  Dilate2x2(&mask, rw, rh);

  // 8 连通域（BFS），统计外接框。
  struct Comp {
    int minX, minY, maxX, maxY;
  };
  std::vector<int> label((size_t)rw * rh, 0);
  std::vector<Comp> comps;
  std::vector<int> stack;
  for (int y = 0; y < rh; ++y) {
    for (int x = 0; x < rw; ++x) {
      int idx = y * rw + x;
      if (!mask[idx] || label[idx] != 0) continue;
      int labelId = (int)comps.size() + 1;
      comps.push_back({x, y, x, y});
      label[idx] = labelId;
      stack.clear();
      stack.push_back(idx);
      while (!stack.empty()) {
        int cur = stack.back();
        stack.pop_back();
        int cy = cur / rw;
        int cx = cur - cy * rw;
        Comp* c = &comps[labelId - 1];
        if (cx < c->minX) c->minX = cx;
        if (cx > c->maxX) c->maxX = cx;
        if (cy < c->minY) c->minY = cy;
        if (cy > c->maxY) c->maxY = cy;
        for (int dy = -1; dy <= 1; ++dy) {
          for (int dx = -1; dx <= 1; ++dx) {
            if (dx == 0 && dy == 0) continue;
            int nx = cx + dx;
            int ny = cy + dy;
            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue;
            int ni = ny * rw + nx;
            if (mask[ni] && label[ni] == 0) {
              label[ni] = labelId;
              stack.push_back(ni);
            }
          }
        }
      }
    }
  }

  boxes->clear();
  for (const Comp& c : comps) {
    const int wPx = c.maxX - c.minX;
    const int hPx = c.maxY - c.minY;
    if (std::min(wPx, hPx) < DET_MIN_SIZE) continue;
    double sum = 0.0;
    int cnt = 0;
    for (int y = c.minY; y <= c.maxY; ++y) {
      for (int x = c.minX; x <= c.maxX; ++x) {
        sum += pred[(size_t)y * rw + x];
        ++cnt;
      }
    }
    float score = cnt == 0 ? 0.0f : (float)(sum / cnt);
    if (score < DET_BOX_THRESH) continue;
    double area = (double)wPx * (double)hPx;
    double perim = 2.0 * ((double)wPx + (double)hPx);
    double dist = perim <= 0 ? 0.0 : area * DET_UNCLIP_RATIO / perim;
    double ex0 = c.minX - dist;
    double ey0 = c.minY - dist;
    double ex1 = c.maxX + dist;
    double ey1 = c.maxY + dist;
    if (std::min(ex1 - ex0, ey1 - ey0) < DET_MIN_SIZE + 2) continue;
    int x0 = (int)std::llround(ex0 / rw * w);
    int y0 = (int)std::llround(ey0 / rh * h);
    int x1 = (int)std::llround(ex1 / rw * w);
    int y1 = (int)std::llround(ey1 / rh * h);
    x0 = std::max(0, std::min(x0, w));
    y0 = std::max(0, std::min(y0, h));
    x1 = std::max(0, std::min(x1, w));
    y1 = std::max(0, std::min(y1, h));
    if (std::abs(x1 - x0) <= 3 || std::abs(y1 - y0) <= 3) continue;
    PpLineBox box;
    box.x0 = x0;
    box.y0 = y0;
    box.x1 = x1;
    box.y1 = y1;
    box.score = score;
    boxes->push_back(box);
  }
  return true;
}

std::string PpOcrEngine::ProbeJson(std::string* err) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!ready_) {
    *err = "engine not initialized";
    return "";
  }
  long t0 = NowMs();
  std::vector<float> detIn((size_t)3 * 736 * 736, 0.0f);
  std::vector<float> detOut;
  if (!RunDetTensor(detIn.data(), 736, 736, &detOut, err)) return "";
  long detMs = NowMs() - t0;
  float detMax = 0.0f;
  for (float v : detOut) detMax = std::max(detMax, v);
  long t1 = NowMs();
  std::string text;
  float score = 0.0f;
  std::vector<float> recIn((size_t)3 * 48 * 320, 0.0f);
  if (!RunRecTensor(recIn.data(), 320, &text, &score, err)) return "";
  long recMs = NowMs() - t1;
  char buf[512];
  snprintf(buf, sizeof(buf),
           "{\"ok\":true,\"detOut\":%d,\"detMax\":%.4f,\"detMs\":%ld,\"recTextLen\":%d,"
           "\"recScore\":%.4f,\"recMs\":%ld}",
           (int)detOut.size(), detMax, detMs, (int)text.size(), score, recMs);
  return std::string(buf);
}

std::string PpOcrEngine::RecognizeJson(const uint8_t* rgba, int width, int height, bool linePass,
                                       std::string* err) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!ready_) {
    *err = "engine not initialized";
    return "";
  }
  if (rgba == nullptr || width <= 0 || height <= 0) {
    *err = "invalid image buffer";
    return "";
  }
  long tStart = NowMs();
  std::vector<PpLineBox> boxes;
  long detMs = 0;
  if (!RunDet(rgba, width, height, &boxes, &detMs, err)) return "";

  // sorted_boxes：先按 y0 稳定排序，dy>=10 分行，再按 (行, x0) 稳定排序。
  {
    std::vector<int> idx(boxes.size());
    for (size_t i = 0; i < idx.size(); ++i) idx[i] = (int)i;
    std::stable_sort(idx.begin(), idx.end(), [&](int a, int b) { return boxes[a].y0 < boxes[b].y0; });
    std::vector<int> lineIds(boxes.size(), 0);
    int line = 0;
    for (size_t i = 0; i < idx.size(); ++i) {
      if (i > 0 && boxes[idx[i]].y0 - boxes[idx[i - 1]].y0 >= 10) ++line;
      lineIds[i] = line;
    }
    struct Keyed {
      int line;
      int x0;
      PpLineBox box;
    };
    std::vector<Keyed> keyed;
    keyed.reserve(idx.size());
    for (size_t i = 0; i < idx.size(); ++i) {
      keyed.push_back({lineIds[i], boxes[idx[i]].x0, boxes[idx[i]]});
    }
    std::stable_sort(keyed.begin(), keyed.end(), [](const Keyed& a, const Keyed& b) {
      if (a.line != b.line) return a.line < b.line;
      return a.x0 < b.x0;
    });
    std::vector<PpLineBox> sorted;
    sorted.reserve(keyed.size());
    for (const Keyed& k : keyed) sorted.push_back(k.box);
    boxes.swap(sorted);
  }

  long recStart = NowMs();
  struct OutLine {
    std::string text;
    float score;
    PpLineBox box;
  };
  std::vector<OutLine> lines;
  int retried = 0;
  std::vector<uint8_t> crop;
  for (const PpLineBox& box : boxes) {
    int x0 = std::max(0, std::min(box.x0, width - 1));
    int y0 = std::max(0, std::min(box.y0, height - 1));
    int x1 = std::max(x0 + 1, std::min(box.x1, width));
    int y1 = std::max(y0 + 1, std::min(box.y1, height));
    int cw = 0;
    int ch = 0;
    CropRgba(rgba, width, height, x0, y0, x1, y1, &crop, &cw, &ch);
    if (cw < 2 || ch < 2) continue;
    std::string text;
    float score = 0.0f;
    if (!RunRec(crop.data(), cw, ch, &text, &score, err)) return "";
    if (linePass && score < DEFAULT_RETRY_BELOW && retried < DEFAULT_MAX_RETRIES) {
      int pad = std::max(4, (int)std::lround(std::min(cw, ch) * 0.12));
      int rx0 = std::max(0, x0 - pad);
      int ry0 = std::max(0, y0 - pad);
      int rx1 = std::min(width, x1 + pad);
      int ry1 = std::min(height, y1 + pad);
      int rw = 0;
      int rh = 0;
      std::vector<uint8_t> rcrop;
      CropRgba(rgba, width, height, rx0, ry0, rx1, ry1, &rcrop, &rw, &rh);
      float scale = rh < 40 ? 2.0f : 1.6f;
      int sw = std::max(2, (int)(rw * scale));
      int sh = std::max(2, (int)(rh * scale));
      std::vector<uint8_t> scaled;
      UpscaleRgba(rcrop, rw, rh, sw, sh, &scaled);
      std::string text2;
      float score2 = 0.0f;
      if (RunRec(scaled.data(), sw, sh, &text2, &score2, err)) {
        ++retried;
        std::string betterText;
        float betterScore = 0.0f;
        BetterReading(text, score, text2, score2, DEFAULT_RETRY_BELOW, &betterText, &betterScore);
        text = betterText;
        score = betterScore;
      }
    }
    if (!text.empty() && score >= TEXT_SCORE) {
      lines.push_back({text, score, box});
    }
  }
  long recMs = NowMs() - recStart;
  long totalMs = NowMs() - tStart;

  std::string json = "{\"ok\":true,\"engine\":\"PP-OCR\",\"lines\":[";
  for (size_t i = 0; i < lines.size(); ++i) {
    if (i > 0) json += ",";
    // 注意：不能用定长 snprintf 拼 text（长行会被截断成非法 JSON，ArkTS JSON.parse 直接失败）。
    json += "{\"text\":\"";
    json += JsonEscape(lines[i].text);
    char meta[192];
    snprintf(meta, sizeof(meta), "\",\"score\":%.4f,\"x0\":%d,\"y0\":%d,\"x1\":%d,\"y1\":%d}",
             lines[i].score, lines[i].box.x0, lines[i].box.y0, lines[i].box.x1, lines[i].box.y1);
    json += meta;
  }
  char tail[320];
  snprintf(tail, sizeof(tail),
           "],\"lineCount\":%d,\"detBoxes\":%d,\"retried\":%d,\"detMs\":%ld,\"recMs\":%ld,"
           "\"totalMs\":%ld,\"imageWidth\":%d,\"imageHeight\":%d}",
           (int)lines.size(), (int)boxes.size(), retried, detMs, recMs, totalMs, width, height);
  json += tail;
  return json;
}
