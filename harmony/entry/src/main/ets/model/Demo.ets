// 预置的 3 道示例错题：数学公式 / 英语 / 形近词。
// 这是给第一次打开应用的人演示“错题复习”怎么用的示例内容，不是任何识别结果，
// 也不会计入学习统计（答对答错都不写错题本和正确率）。
export interface DemoQuestion {
  id: string;
  kind: string;
  prompt: string;
  options: string[];
  answerIndex: number;
  explanation: string;
}

export const DEMO_SECONDS: number = 60;

export const DEMO_QUESTIONS: DemoQuestion[] = [
  {
    id: 'demo-math-1',
    kind: '数学公式',
    prompt: '圆的面积公式是？',
    options: ['πr²', '2πr', 'πd', '4πr²'],
    answerIndex: 0,
    explanation: '圆的面积 S = πr²；“2πr / πd” 是周长，别记混。'
  },
  {
    id: 'demo-en-1',
    kind: '英语',
    prompt: 'consistent 的中文释义是？',
    options: ['adj. 一致的；持续的', 'v. 放弃；抛弃', 'n. 优势；有利条件', 'adj. 焦虑的；渴望的'],
    answerIndex: 0,
    explanation: 'consistent = 一致的、持续的；例：Her progress is consistent and steady.'
  },
  {
    id: 'demo-word-1',
    kind: '形近词',
    prompt: '形近词辨析：affect 通常是什么词性、什么意思？',
    options: ['动词：影响', '名词：效果', '形容词：有效的', '副词：事实上'],
    answerIndex: 0,
    explanation: 'affect 是动词“影响”；effect 才是名词“效果、影响”。'
  }
];
