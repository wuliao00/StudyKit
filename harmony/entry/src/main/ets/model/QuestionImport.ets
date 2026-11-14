import { QuestionItem } from './QuestionStore';

// 批量粘贴导入的解析与校验逻辑（纯函数，不依赖 UI / 网络）。
// 支持两种格式：
//   1) 每题一行：题干|A|B|C|D|答案字母|解析（最后一段解析可省略）
//   2) 分块粘贴：题干行 + A. B. C. D. 选项 + 答案：X + 解析：...（块之间空行分隔）
// 兼容：全角竖线 ｜、序号前缀（1. / 2、）、选项前缀（A. / A、/ A)）、
//       全角冒号、注释行（# 或 // 开头）、空行、答案写字母或 1-4 数字。

export const IMPORT_SOURCE: string = '批量粘贴';

export interface ImportDraft {
  key: string;
  subject: string;
  stem: string;
  options: string[];
  answer: number;
  explanation: string;
  source: string;
  status: string;      // ok / duplicate / edited
  message: string;
  lineLabel: string;
}

export interface ImportError {
  lineLabel: string;
  content: string;
  message: string;
}

export interface ParseReport {
  drafts: ImportDraft[];
  errors: ImportError[];
  totalLines: number;
  blankLines: number;
  commentLines: number;
  duplicateCount: number;
}

export interface DraftCheck {
  ok: boolean;
  duplicate: boolean;
  message: string;
  options: string[];
}

export class QuestionImportParser {
  static readonly STATUS_OK: string = 'ok';
  static readonly STATUS_DUPLICATE: string = 'duplicate';
  static readonly STATUS_EDITED: string = 'edited';

  static newKey(): string {
    return 'draft-' + Date.now().toString() + '-' + Math.floor(Math.random() * 10000).toString();
  }

  // 用于去重的归一化 key：同一学科 + 题干（去空白、去首尾标点、英文小写）
  static questionKey(subject: string, stem: string): string {
    const subj: string = subject.trim();
    let text: string = stem.trim().toLowerCase();
    text = text.replace(/\s+/g, '');
    text = text.replace(/[。．.？?！!；;：:]+$/g, '');
    return subj + '||' + text;
  }

  static existingKeys(existing: QuestionItem[]): string[] {
    const keys: string[] = [];
    for (let i = 0; i < existing.length; i++) {
      keys.push(QuestionImportParser.questionKey(existing[i].subject, existing[i].stem));
    }
    return keys;
  }

  private static isComment(line: string): boolean {
    const t: string = line.trim();
    return t.startsWith('#') || t.startsWith('//');
  }

  private static stripNumbering(text: string): string {
    let t: string = text.trim();
    t = t.replace(/^\d+\s*[.、．)）]\s*/, '');
    t = t.replace(/^(题干|题目)\s*[：:]\s*/, '');
    return t.trim();
  }

  // 解析一题一行：题干|A|B|C|D|答案|解析
  private static parseLineRecord(text: string, subject: string, lineLabel: string): ImportDraft | null {
    const rawParts: string[] = text.split(/[|｜]/);
    if (rawParts.length < 6) {
      return null;
    }
    const stem: string = QuestionImportParser.stripNumbering(rawParts[0]);
    const options: string[] = [];
    for (let i = 1; i <= 4; i++) {
      options.push(rawParts[i].trim());
    }
    const answer: number = QuestionImportParser.answerFromText(rawParts[5], options.length);
    const explanation: string = rawParts.length >= 7 ? rawParts[6].trim() : '';
    const draft: ImportDraft = {
      key: QuestionImportParser.newKey(),
      subject: subject,
      stem: stem,
      options: options,
      answer: answer,
      explanation: explanation,
      source: IMPORT_SOURCE,
      status: QuestionImportParser.STATUS_OK,
      message: '',
      lineLabel: lineLabel
    };
    return draft;
  }

  // 解析一整块：题干行 + 选项 + 答案 + 解析
  private static parseBlock(lines: string[], startLine: number, endLine: number,
    subject: string): ImportDraft | null {
    const stemParts: string[] = [];
    const options: string[] = ['', '', '', ''];
    let answer: number = -1;
    const explanationParts: string[] = [];
    let mode: number = 0; // 0 题干 1 解析
    for (let i = 0; i < lines.length; i++) {
      const line: string = lines[i].trim();
      if (line.length === 0) {
        continue;
      }
      if (QuestionImportParser.isOptionLine(line)) {
        const letter: string = line.substring(0, 1).toUpperCase();
        const index: number = letter.charCodeAt(0) - 'A'.charCodeAt(0);
        if (index >= 0 && index < 4) {
          options[index] = QuestionImportParser.stripOptionPrefix(line);
          mode = 0;
        } else {
          stemParts.push(line);
        }
        continue;
      }
      if (/^(答案|正确答案|参考答案)\s*[：:]/.test(line)) {
        const value: string = line.replace(/^(答案|正确答案|参考答案)\s*[：:]\s*/, '');
        answer = QuestionImportParser.answerFromText(value, 4);
        continue;
      }
      if (/^(解析|答案解析|解答)\s*[：:]?/.test(line)) {
        const value: string = line.replace(/^(解析|答案解析|解答)\s*[：:]?\s*/, '');
        if (value.length > 0) {
          explanationParts.push(value);
        }
        mode = 1;
        continue;
      }
      if (mode === 1) {
        explanationParts.push(line);
      } else {
        stemParts.push(line);
      }
    }
    let stem: string = QuestionImportParser.stripNumbering(stemParts.join(' '));
    if (answer < 0) {
      // 块里可能把答案写在最后且没有“答案：”前缀，尝试在解析文本里找单独的字母
      answer = 0;
    }
    const draft: ImportDraft = {
      key: QuestionImportParser.newKey(),
      subject: subject,
      stem: stem,
      options: options,
      answer: answer,
      explanation: explanationParts.join(' ').trim(),
      source: IMPORT_SOURCE,
      status: QuestionImportParser.STATUS_OK,
      message: '',
      lineLabel: '第 ' + startLine.toString() + '-' + endLine.toString() + ' 行'
    };
    return draft;
  }

  private static isOptionLine(line: string): boolean {
    if (line.length < 3) {
      return false;
    }
    const first: string = line.substring(0, 1).toUpperCase();
    if (first < 'A' || first > 'H') {
      return false;
    }
    const second: string = line.substring(1, 2);
    return second === '.' || second === '．' || second === '、' || second === ':' || second === '：'
      || second === ')' || second === '）' || second === ' ';
  }

  private static stripOptionPrefix(line: string): string {
    let start: number = 1;
    while (start < line.length) {
      const ch: string = line.substring(start, start + 1);
      if (ch === '.' || ch === '．' || ch === '、' || ch === ':' || ch === '：'
        || ch === ')' || ch === '）' || ch === ' ') {
        start++;
      } else {
        break;
      }
    }
    const text: string = line.substring(start).trim();
    return text.length > 0 ? text : line.trim();
  }

  static answerFromText(text: string, optionCount: number): number {
    const t: string = text.trim().toUpperCase();
    if (t.length === 0) {
      return -1;
    }
    // 宽容处理“B / b / 2 / 选B / (C) / 答案：D”等写法
    for (let i = 0; i < t.length; i++) {
      const ch: string = t.substring(i, i + 1);
      if (ch >= 'A' && ch <= 'H') {
        const index: number = ch.charCodeAt(0) - 'A'.charCodeAt(0);
        if (index < optionCount) {
          return index;
        }
      }
      if (ch >= '1' && ch <= '9') {
        const numeric: number = Number(ch);
        if (numeric >= 1 && numeric <= optionCount) {
          return numeric - 1;
        }
      }
    }
    return -1;
  }

  static validateDraft(subject: string, stem: string, options: string[], answer: number): DraftCheck {
    const result: DraftCheck = { ok: false, duplicate: false, message: '', options: [] };
    const stemText: string = stem.trim();
    if (stemText.length === 0) {
      result.message = '题干为空';
      return result;
    }
    const cleaned: string[] = [];
    let lastNonEmpty: number = -1;
    for (let i = 0; i < options.length && i < 4; i++) {
      const opt: string = options[i].trim();
      if (opt.length > 0) {
        lastNonEmpty = i;
      }
    }
    if (lastNonEmpty < 1) {
      result.message = '至少需要 A、B 两个选项';
      return result;
    }
    for (let i = 0; i <= lastNonEmpty; i++) {
      const opt: string = options[i].trim();
      if (opt.length === 0) {
        result.message = '选项不能跳空（B/C/D 中间不能留空）';
        return result;
      }
      cleaned.push(opt);
    }
    if (answer < 0 || answer >= cleaned.length) {
      result.message = '正确答案超出选项范围';
      return result;
    }
    result.ok = true;
    result.options = cleaned;
    return result;
  }

  // 主入口：把粘贴文本解析为预览草稿 + 错误行 + 去重结果
  static parse(rawText: string, subject: string, existing: QuestionItem[]): ParseReport {
    const report: ParseReport = {
      drafts: [],
      errors: [],
      totalLines: 0,
      blankLines: 0,
      commentLines: 0,
      duplicateCount: 0
    };
    const lines: string[] = rawText.split('\n');
    report.totalLines = lines.length;
    const existingKeys: string[] = QuestionImportParser.existingKeys(existing);
    const batchKeys: string[] = [];
    let block: string[] = [];
    let blockStart: number = 1;

    const flushBlock = (endLine: number): void => {
      if (block.length === 0) {
        return;
      }
      const content: string = block.join('\n');
      const draft: ImportDraft | null = QuestionImportParser.parseBlock(block, blockStart, endLine, subject);
      block = [];
      if (draft === null) {
        report.errors.push({ lineLabel: '第 ' + blockStart.toString() + '-' + endLine.toString() + ' 行',
          content: content, message: '无法解析这一块内容，请检查格式' });
        return;
      }
      QuestionImportParser.acceptDraft(report, draft, existingKeys, batchKeys);
    };

    for (let i = 0; i < lines.length; i++) {
      const rawLine: string = lines[i].replace(/\r$/, '');
      const line: string = rawLine.trim();
      const lineNo: number = i + 1;
      if (line.length === 0) {
        report.blankLines++;
        flushBlock(lineNo - 1);
        continue;
      }
      if (QuestionImportParser.isComment(line)) {
        report.commentLines++;
        continue;
      }
      if (line.indexOf('|') >= 0 || line.indexOf('｜') >= 0) {
        flushBlock(lineNo - 1);
        const draft: ImportDraft | null = QuestionImportParser.parseLineRecord(line, subject, '第 ' + lineNo.toString() + ' 行');
        if (draft === null) {
          report.errors.push({ lineLabel: '第 ' + lineNo.toString() + ' 行', content: line,
            message: '一行一题需要 6 段：题干|A|B|C|D|答案|解析（解析可省略）' });
        } else {
          QuestionImportParser.acceptDraft(report, draft, existingKeys, batchKeys);
        }
        continue;
      }
      if (block.length > 0 && /^\d+\s*[.、．)）]/.test(line)) {
        // 没写空行但用 1. / 2、序号开了新题，也宽容地切成新块
        flushBlock(lineNo - 1);
      }
      if (block.length === 0) {
        blockStart = lineNo;
      }
      block.push(line);
    }
    flushBlock(lines.length);
    return report;
  }

  private static acceptDraft(report: ParseReport, draft: ImportDraft, existingKeys: string[],
    batchKeys: string[]): void {
    const check: DraftCheck = QuestionImportParser.validateDraft(draft.subject, draft.stem, draft.options, draft.answer);
    if (!check.ok) {
      report.errors.push({ lineLabel: draft.lineLabel, content: draft.stem,
        message: check.message });
      return;
    }
    draft.options = check.options;
    const key: string = QuestionImportParser.questionKey(draft.subject, draft.stem);
    if (existingKeys.indexOf(key) >= 0) {
      draft.status = QuestionImportParser.STATUS_DUPLICATE;
      draft.message = '题库里已有同题，默认跳过（可编辑题干后导入）';
      report.duplicateCount++;
    } else if (batchKeys.indexOf(key) >= 0) {
      draft.status = QuestionImportParser.STATUS_DUPLICATE;
      draft.message = '本次粘贴内重复，默认跳过';
      report.duplicateCount++;
    } else {
      draft.status = QuestionImportParser.STATUS_OK;
      draft.message = '可导入';
    }
    batchKeys.push(key);
    report.drafts.push(draft);
  }

  // 保存前用最新题库再校验一次（用户可能逐条编辑过）
  static checkDraft(draft: ImportDraft, existingKeys: string[]): DraftCheck {
    const check: DraftCheck = QuestionImportParser.validateDraft(draft.subject, draft.stem, draft.options, draft.answer);
    if (!check.ok) {
      return check;
    }
    const key: string = QuestionImportParser.questionKey(draft.subject, draft.stem);
    if (existingKeys.indexOf(key) >= 0) {
      check.duplicate = true;
      check.message = '题库里已有同题，已跳过';
    }
    return check;
  }
}
