export interface WordItem {
  word: string;
  phonetic: string;
  meaning: string;
  example: string;
  book: string;
}

export interface WordBook {
  id: string;
  name: string;
  caption: string;
}

// 内置 66 个常用词，分 CET4 / CET6 / 考研 / 基础 四本词书；全部离线。
export const WORD_BOOKS: WordBook[] = [
  { id: 'basic', name: '基础', caption: '高频基础词' },
  { id: 'cet4', name: 'CET4', caption: '四级核心词' },
  { id: 'cet6', name: 'CET6', caption: '六级进阶词' },
  { id: 'ky', name: '考研', caption: '考研高频词' }
];

export const BOOK_ALL: string = 'all';
export const USER_BOOK_CUSTOM_ID: string = 'custom';

// 用户自定义词库（Lead 集成：WordManagePage 写入后调用 setUserWords）。
let USER_WORDS: WordItem[] = [];

export function setUserWords(words: WordItem[]): void {
  USER_WORDS = words;
}

export function allWords(): WordItem[] {
  return WORD_BANK.concat(USER_WORDS);
}

export const WORD_BANK: WordItem[] = [
  { word: 'abandon', phonetic: '/əˈbændən/', meaning: 'v. 放弃；抛弃', example: 'He had to abandon the plan because of the storm.', book: 'cet4' },
  { word: 'absorb', phonetic: '/əbˈzɔːrb/', meaning: 'v. 吸收；理解', example: 'Plants absorb water through their roots.', book: 'cet4' },
  { word: 'accurate', phonetic: '/ˈækjərət/', meaning: 'adj. 准确的；精确的', example: 'The report gives an accurate picture of the market.', book: 'cet4' },
  { word: 'achieve', phonetic: '/əˈtʃiːv/', meaning: 'v. 实现；达到', example: 'She worked hard to achieve her goal.', book: 'cet4' },
  { word: 'adapt', phonetic: '/əˈdæpt/', meaning: 'v. 适应；改编', example: 'It takes time to adapt to a new environment.', book: 'basic' },
  { word: 'admire', phonetic: '/ədˈmaɪər/', meaning: 'v. 钦佩；欣赏', example: 'I admire her courage in facing difficulties.', book: 'basic' },
  { word: 'advantage', phonetic: '/ədˈvæntɪdʒ/', meaning: 'n. 优势；有利条件', example: 'Speaking two languages is a big advantage.', book: 'cet4' },
  { word: 'anxious', phonetic: '/ˈæŋkʃəs/', meaning: 'adj. 焦虑的；渴望的', example: 'She felt anxious before the exam.', book: 'cet4' },
  { word: 'approach', phonetic: '/əˈproʊtʃ/', meaning: 'n. 方法 v. 接近', example: 'We need a new approach to this problem.', book: 'cet4' },
  { word: 'arrange', phonetic: '/əˈreɪndʒ/', meaning: 'v. 安排；整理', example: 'Let us arrange a meeting for next week.', book: 'cet4' },
  { word: 'assume', phonetic: '/əˈsuːm/', meaning: 'v. 假设；认为', example: 'Do not assume that everyone agrees with you.', book: 'cet4' },
  { word: 'attach', phonetic: '/əˈtætʃ/', meaning: 'v. 附上；连接', example: 'Please attach your resume to the email.', book: 'cet4' },
  { word: 'attempt', phonetic: '/əˈtempt/', meaning: 'v. 尝试 n. 努力', example: 'He made an attempt to fix the machine.', book: 'basic' },
  { word: 'attitude', phonetic: '/ˈætɪtuːd/', meaning: 'n. 态度；看法', example: 'A positive attitude helps you learn faster.', book: 'basic' },
  { word: 'available', phonetic: '/əˈveɪləbl/', meaning: 'adj. 可用的；有空的', example: 'The room is available from Monday.', book: 'basic' },
  { word: 'benefit', phonetic: '/ˈbenɪfɪt/', meaning: 'n. 好处 v. 受益', example: 'Exercise brings lasting health benefits.', book: 'basic' },
  { word: 'boost', phonetic: '/buːst/', meaning: 'v. 提升 n. 推动', example: 'A short walk can boost your energy.', book: 'cet4' },
  { word: 'brief', phonetic: '/briːf/', meaning: 'adj. 简短的 v. 简要介绍', example: 'Could you give a brief summary of the report?', book: 'basic' },
  { word: 'capture', phonetic: '/ˈkæptʃər/', meaning: 'v. 捕捉；记录', example: 'The photo captures the beauty of the lake.', book: 'cet4' },
  { word: 'challenge', phonetic: '/ˈtʃælɪndʒ/', meaning: 'n. 挑战 v. 质疑', example: 'Learning a language is a rewarding challenge.', book: 'basic' },
  { word: 'claim', phonetic: '/kleɪm/', meaning: 'v. 声称；索取 n. 主张', example: 'The company claims its product is the fastest.', book: 'cet4' },
  { word: 'commit', phonetic: '/kəˈmɪt/', meaning: 'v. 承诺；投入', example: 'She committed herself to finishing the project.', book: 'cet4' },
  { word: 'compare', phonetic: '/kəmˈper/', meaning: 'v. 比较；对比', example: 'Compare the two answers before you decide.', book: 'basic' },
  { word: 'compete', phonetic: '/kəmˈpiːt/', meaning: 'v. 竞争；比赛', example: 'Small shops find it hard to compete with big ones.', book: 'cet4' },
  { word: 'complex', phonetic: '/kəmˈpleks/', meaning: 'adj. 复杂的 n. 综合体', example: 'The human brain is a complex system.', book: 'cet6' },
  { word: 'concentrate', phonetic: '/ˈkɑːnsntreɪt/', meaning: 'v. 集中；专注', example: 'Turn off your phone to concentrate on reading.', book: 'cet6' },
  { word: 'confirm', phonetic: '/kənˈfɜːrm/', meaning: 'v. 确认；证实', example: 'Please confirm your booking by Friday.', book: 'basic' },
  { word: 'consistent', phonetic: '/kənˈsɪstənt/', meaning: 'adj. 一致的；持续的', example: 'Her progress is consistent and steady.', book: 'cet6' },
  { word: 'contribute', phonetic: '/kənˈtrɪbjuːt/', meaning: 'v. 贡献；促成', example: 'Everyone can contribute to the discussion.', book: 'cet4' },
  { word: 'convince', phonetic: '/kənˈvɪns/', meaning: 'v. 说服；使确信', example: 'The evidence convinced him to change his mind.', book: 'cet4' },
  { word: 'cooperate', phonetic: '/koʊˈɑːpəreɪt/', meaning: 'v. 合作；配合', example: 'The two teams cooperate on the research.', book: 'cet6' },
  { word: 'crucial', phonetic: '/ˈkruːʃl/', meaning: 'adj. 关键的；至关重要的', example: 'Good sleep is crucial for memory.', book: 'cet6' },
  { word: 'curious', phonetic: '/ˈkjʊriəs/', meaning: 'adj. 好奇的', example: 'Children are naturally curious about the world.', book: 'cet4' },
  { word: 'deliberate', phonetic: '/dɪˈlɪbərət/', meaning: 'adj. 故意的；深思熟虑的', example: 'It was a deliberate choice, not an accident.', book: 'cet6' },
  { word: 'demonstrate', phonetic: '/ˈdemənstreɪt/', meaning: 'v. 证明；演示', example: 'The teacher demonstrated the experiment step by step.', book: 'cet6' },
  { word: 'depend', phonetic: '/dɪˈpend/', meaning: 'v. 依赖；取决于', example: 'The result depends on how hard you practise.', book: 'basic' },
  { word: 'deserve', phonetic: '/dɪˈzɜːrv/', meaning: 'v. 应得；值得', example: 'You deserve a break after all that work.', book: 'ky' },
  { word: 'despite', phonetic: '/dɪˈspaɪt/', meaning: 'prep. 尽管；不管', example: 'Despite the rain, the match went on.', book: 'ky' },
  { word: 'develop', phonetic: '/dɪˈveləp/', meaning: 'v. 发展；培养', example: 'Reading helps develop your imagination.', book: 'basic' },
  { word: 'distinguish', phonetic: '/dɪˈstɪŋɡwɪʃ/', meaning: 'v. 区分；辨别', example: 'Can you distinguish between the two sounds?', book: 'cet6' },
  { word: 'efficient', phonetic: '/ɪˈfɪʃnt/', meaning: 'adj. 高效的；效率高的', example: 'This method is simple and efficient.', book: 'cet6' },
  { word: 'eliminate', phonetic: '/ɪˈlɪmɪneɪt/', meaning: 'v. 消除；淘汰', example: 'The new rules eliminate unnecessary steps.', book: 'cet6' },
  { word: 'emphasize', phonetic: '/ˈemfəsaɪz/', meaning: 'v. 强调；重视', example: 'The coach emphasized the importance of teamwork.', book: 'cet6' },
  { word: 'essential', phonetic: '/ɪˈsenʃl/', meaning: 'adj. 必要的；本质的', example: 'Water is essential for life.', book: 'cet6' },
  { word: 'establish', phonetic: '/ɪˈstæblɪʃ/', meaning: 'v. 建立；确立', example: 'They established the company in 2010.', book: 'cet4' },
  { word: 'estimate', phonetic: '/ˈestɪmeɪt/', meaning: 'v. 估计 n. 估算', example: 'Engineers estimate the repair will take a week.', book: 'cet4' },
  { word: 'evidence', phonetic: '/ˈevɪdəns/', meaning: 'n. 证据；证明', example: 'There is strong evidence that exercise improves mood.', book: 'cet6' },
  { word: 'expand', phonetic: '/ɪkˈspænd/', meaning: 'v. 扩大；扩展', example: 'The city plans to expand the subway system.', book: 'cet6' },
  { word: 'explore', phonetic: '/ɪkˈsplɔːr/', meaning: 'v. 探索；调查', example: 'Let us explore the old town on foot.', book: 'cet6' },
  { word: 'familiar', phonetic: '/fəˈmɪliər/', meaning: 'adj. 熟悉的', example: 'Her face looks familiar to me.', book: 'cet6' },
  { word: 'flexible', phonetic: '/ˈfleksəbl/', meaning: 'adj. 灵活的；易弯曲的', example: 'Remote work makes the schedule more flexible.', book: 'ky' },
  { word: 'focus', phonetic: '/ˈfoʊkəs/', meaning: 'v. 集中 n. 焦点', example: 'Focus on one task at a time.', book: 'basic' },
  { word: 'frequent', phonetic: '/ˈfriːkwənt/', meaning: 'adj. 频繁的；经常的', example: 'Frequent practice makes speaking easier.', book: 'ky' },
  { word: 'genuine', phonetic: '/ˈdʒenjuɪn/', meaning: 'adj. 真正的；真诚的', example: 'She showed genuine interest in the topic.', book: 'ky' },
  { word: 'gradual', phonetic: '/ˈɡrædʒuəl/', meaning: 'adj. 逐渐的；逐步的', example: 'Learning is a gradual process.', book: 'ky' },
  { word: 'guarantee', phonetic: '/ˌɡærənˈtiː/', meaning: 'v. 保证 n. 担保', example: 'Hard work does not guarantee success by itself.', book: 'ky' },
  { word: 'ignore', phonetic: '/ɪɡˈnɔːr/', meaning: 'v. 忽视；不理会', example: 'Do not ignore the small warning signs.', book: 'ky' },
  { word: 'illustrate', phonetic: '/ˈɪləstreɪt/', meaning: 'v. 说明；图解', example: 'This chart illustrates the change over time.', book: 'ky' },
  { word: 'impact', phonetic: '/ˈɪmpækt/', meaning: 'n. 影响 v. 冲击', example: 'The advice had a big impact on my study habits.', book: 'ky' },
  { word: 'improve', phonetic: '/ɪmˈpruːv/', meaning: 'v. 改善；提高', example: 'Daily review improves long-term memory.', book: 'ky' },
  { word: 'include', phonetic: '/ɪnˈkluːd/', meaning: 'v. 包括；包含', example: 'The price includes breakfast.', book: 'basic' },
  { word: 'increase', phonetic: '/ɪnˈkriːs/', meaning: 'v. 增加 n. 增长', example: 'Sales increased by ten percent last month.', book: 'basic' },
  { word: 'influence', phonetic: '/ˈɪnfluəns/', meaning: 'n. 影响 v. 影响', example: 'Friends can influence your study habits.', book: 'basic' },
  { word: 'insist', phonetic: '/ɪnˈsɪst/', meaning: 'v. 坚持；坚决要求', example: 'She insisted on paying for the meal.', book: 'ky' },
  { word: 'inspire', phonetic: '/ɪnˈspaɪər/', meaning: 'v. 激励；启发', example: 'His story inspired me to keep going.', book: 'ky' },
  { word: 'involve', phonetic: '/ɪnˈvɑːlv/', meaning: 'v. 涉及；使参与', example: 'The job involves a lot of teamwork.', book: 'ky' }
];

export function booksOf(): WordBook[] {
  if (USER_WORDS.length === 0) {
    return WORD_BOOKS;
  }
  return WORD_BOOKS.concat([{ id: USER_BOOK_CUSTOM_ID, name: '自定义', caption: '我导入的词' }]);
}

export function bookName(bookId: string): string {
  if (bookId === BOOK_ALL) {
    return '全部词书';
  }
  if (bookId === USER_BOOK_CUSTOM_ID) {
    return '自定义';
  }
  for (let i = 0; i < WORD_BOOKS.length; i++) {
    if (WORD_BOOKS[i].id === bookId) {
      return WORD_BOOKS[i].name;
    }
  }
  return '全部词书';
}

export function wordsOfBook(bookId: string): WordItem[] {
  const all: WordItem[] = allWords();
  if (bookId === BOOK_ALL || bookId.length === 0) {
    return all;
  }
  const result: WordItem[] = [];
  for (let i = 0; i < all.length; i++) {
    if (all[i].book === bookId) {
      result.push(all[i]);
    }
  }
  return result;
}

export function bookWordCount(bookId: string): number {
  return wordsOfBook(bookId).length;
}

export function lookupWord(target: string): WordItem | null {
  const all: WordItem[] = allWords();
  for (let i = 0; i < all.length; i++) {
    if (all[i].word === target) {
      return all[i];
    }
  }
  return null;
}
