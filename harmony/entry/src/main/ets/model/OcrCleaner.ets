// OCR 文本清洗：去工具栏噪声、合并题目块、保留可编辑原文。
export interface CleanedOcr {
  usable: string[];
  all: string[];
  filtered: number;
}

export class OcrCleaner {
  // 精确匹配的工具栏/客户端噪声；不按“像数字”误删数学内容。
  static readonly NOISE_EXACT: string[] = [
    'V365', '文件', '开始', '插入', '页面', '编辑', '批注', '工具', '保护', '转换',
    '保存', '分享', '打印', '撤销', '重做', '选择', '查找', '替换', '视图', '帮助',
    '未上云', '已上云', 'PDF转换', '图片转PDF', 'AI排版', 'AI写作', 'AI总结',
    '最近', '更多', '照片', '相册', '相机', '完成', '取消', '返回', '关闭',
    '目录', '书签', '阅读模式', '护眼模式', '适应页面', '适合宽度', '双页', '单页'
  ];

  static readonly NOISE_PATTERNS: RegExp[] = [
    /^V\d+$/i,
    /^第\s*\d+\s*页(\s*[/／]\s*\d+)?$/,
    /^\d+\s*[/／]\s*\d+$/,
    /^\d{1,2}:\d{2}$/,
    /^\d+(\.\d+)?\s*[%％]$/,
    /^[-+]?\d+(\.\d+)?\s*[xX×]$/,
    /^\d+\s*[xX×]\s*\d+$/,
    /^[A-Za-z]?\s*(?:PDF|Word|Excel|PPT)\s*$/i,
    /^(?:WPS|360|夸克|QQ|微信|钉钉)\b/i
  ];

  static normalize(line: string): string {
    let s: string = line.replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
    s = s.replace(/[，,。；;：:]+$/g, '').trim();
    return s;
  }

  static isNoise(line: string): boolean {
    const s: string = OcrCleaner.normalize(line);
    if (s.length === 0) return true;
    for (let i = 0; i < OcrCleaner.NOISE_EXACT.length; i++) {
      if (s === OcrCleaner.NOISE_EXACT[i]) return true;
    }
    for (let i = 0; i < OcrCleaner.NOISE_PATTERNS.length; i++) {
      if (OcrCleaner.NOISE_PATTERNS[i].test(s)) return true;
    }
    return false;
  }

  static isQuestionStart(line: string): boolean {
    const s: string = OcrCleaner.normalize(line);
    return /^\d{1,2}\s*[.、)）]\s*\S/.test(s)
      || /^[一二三四五六七八九十]+\s*[、.]\s*\S/.test(s)
      || /^[（(]\s*[0-9一二三四五六七八九十]+\s*[)）]\s*\S/.test(s);
  }

  static isAnswerLine(line: string): boolean {
    const s: string = OcrCleaner.normalize(line);
    return /^(答案|参考答案|解析|解答|分析|提示|考点|点评|详解)\s*[:：]?/.test(s)
      || /^【(答案|解析|详解|考点)】/.test(s);
  }

  static clean(lines: string[]): CleanedOcr {
    const all: string[] = [];
    const usable: string[] = [];
    for (let i = 0; i < lines.length; i++) {
      const line: string = OcrCleaner.normalize(lines[i]);
      if (line.length === 0) continue;
      all.push(line);
      if (!OcrCleaner.isNoise(line)) usable.push(line);
    }
    // 如果识别结果从文档书中段开始，优先从第一道题/第一个答案行开始，去掉页眉页脚式前言。
    let start: number = 0;
    for (let i = 0; i < usable.length; i++) {
      if (OcrCleaner.isQuestionStart(usable[i]) || OcrCleaner.isAnswerLine(usable[i])) {
        start = i;
        break;
      }
    }
    const focused: string[] = usable.slice(start);
    return { usable: focused.length > 0 ? focused : usable, all: all, filtered: all.length - focused.length };
  }

  static countOptions(lines: string[]): number {
    let n: number = 0;
    for (let i = 0; i < lines.length; i++) {
      if (/^[A-H]\s*[.、．:：)）]\s*\S/.test(OcrCleaner.normalize(lines[i]))) n++;
    }
    return n;
  }
}
