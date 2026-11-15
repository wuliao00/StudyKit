#ifndef PPOCR_ENGINE_H
#define PPOCR_ENGINE_H

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

struct PpLineBox {
  int x0 = 0;
  int y0 = 0;
  int x1 = 0;
  int y1 = 0;
  float score = 0.0f;
};

// PP-OCRv6 det+rec 端侧推理（纯 ONNX Runtime C API）。
// 口径逐条对齐 Android com.studykit.util.PpOcrOnnxEngine：
// - det: limit_type=min/736，长边 1600 内存闸门，32 倍数，BGR，(x/255-0.5)/0.5；
// - DB: thresh=0.3 -> 2x2 膨胀 -> 8 连通域 -> 外接框 -> box_score 均值 0.5 -> unclip 1.6 -> 回映 -> 过滤 -> 排序；
// - rec: H=48，按 max(320/48, w/h) 定宽，右侧补 0，BGR，CTC 贪心（blank=0）。
class PpOcrEngine {
 public:
  static PpOcrEngine& Get();

  // 模型以 rawfile fd + offset + length 传入，不复制到 filesDir；dictText 为 UTF-8 字典全文。
  bool Init(int detFd, int64_t detOffset, int64_t detLen,
            int recFd, int64_t recOffset, int64_t recLen,
            const std::string& dictText, std::string* infoJson, std::string* err);

  bool Ready() const { return ready_; }

  std::string VersionJson() const;
  std::string ProbeJson(std::string* err);
  std::string RecognizeJson(const uint8_t* rgba, int width, int height, bool linePass,
                            std::string* err);

  // 供 NAPI 层读取最近一次初始化信息（JSON）
  std::string info_json_;

 private:
  PpOcrEngine() = default;
  ~PpOcrEngine();
  PpOcrEngine(const PpOcrEngine&) = delete;
  PpOcrEngine& operator=(const PpOcrEngine&) = delete;

  bool EnsureOrt(std::string* err);
  bool CreateSession(const std::vector<uint8_t>& model, bool det, std::string* err);
  bool RunDet(const uint8_t* rgba, int w, int h, std::vector<PpLineBox>* boxes,
              long* detMs, std::string* err);
  bool RunRec(const uint8_t* crop, int w, int h, std::string* text, float* score,
              std::string* err);
  bool RunRecTensor(const float* data, int imgW, std::string* text, float* score,
                    std::string* err);
  bool RunDetTensor(const float* data, int rw, int rh, std::vector<float>* out,
                    std::string* err);
  void Release();

  const OrtApi* api_ = nullptr;
  OrtEnv* env_ = nullptr;
  OrtAllocator* alloc_ = nullptr;
  OrtMemoryInfo* mem_info_ = nullptr;
  OrtSession* det_session_ = nullptr;
  OrtSession* rec_session_ = nullptr;
  std::string det_input_;
  std::string det_output_;
  std::string rec_input_;
  std::string rec_output_;
  std::vector<std::string> chars_;
  bool ready_ = false;
  std::mutex mutex_;
};

#endif  // PPOCR_ENGINE_H
