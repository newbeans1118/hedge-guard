/*
 * 数值与日期格式化（ARCHITECTURE.md §4.1.1）。
 * ⚠️ 金额 / 数量为「字符串化十进制」，禁止用 Number 参与展示运算，避免 JS 精度丢失。
 * 本模块只做「字符串 → 展示字符串」（千分位、定小数位），不做算术、不做业务判定。
 */
import { HG } from './ns.js';

/** 把字符串化十进制格式化为带千分位的展示串，保留原小数位（不做四舍五入）。 */
function decimal(value, digits) {
  if (value === null || value === undefined || value === '') return '';
  const s = String(value).trim();
  if (!/^-?\d+(\.\d+)?$/.test(s)) return s; // 非十进制串（如已含单位）原样返回
  const neg = s.charAt(0) === '-';
  const body = neg ? s.slice(1) : s;
  const dot = body.indexOf('.');
  let intPart = dot === -1 ? body : body.slice(0, dot);
  let fracPart = dot === -1 ? '' : body.slice(dot + 1);
  if (typeof digits === 'number') {
    if (digits <= 0) { fracPart = ''; }
    else if (fracPart.length < digits) { fracPart = fracPart + '0'.repeat(digits - fracPart.length); }
    else if (fracPart.length > digits) { fracPart = fracPart.slice(0, digits); }
  }
  intPart = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return (neg ? '-' : '') + intPart + (fracPart ? '.' + fracPart : '');
}

/** 保留固定小数位（补零 / 截断，不四舍五入）。 */
function fixed(value, digits) { return decimal(value, digits); }

/** 金额（默认 2 位）。 */
function money(value, digits) { return decimal(value, typeof digits === 'number' ? digits : 2); }

/** 数量（默认 4 位，与 §4.1.1 示例 "1234.5600" 对齐）。 */
function qty(value, digits) { return decimal(value, typeof digits === 'number' ? digits : 4); }

/** 比率：输入字符串化比率（0~1）或已带 % 的串，输出百分比展示。 */
function pct(value) {
  if (value === null || value === undefined || value === '') return '';
  const s = String(value).trim();
  if (s.endsWith('%')) return s;
  if (!/^-?\d+(\.\d+)?$/.test(s)) return s;
  const num = Number(s); // 比率展示允许 Number（非金额/数量）
  if (!isFinite(num)) return s;
  return (num * 100).toFixed(2) + '%';
}

/** 纯日期 yyyy-MM-dd。 */
function date(value) {
  if (!value) return '';
  const s = String(value);
  return s.length >= 10 ? s.slice(0, 10) : s;
}

/** 日期时间 ISO-8601（本地时区原样展示，仅做 "T" → 空格）。 */
function dateTime(value) {
  if (!value) return '';
  return String(value).replace('T', ' ').slice(0, 19);
}

/** HTML 转义（用于所有插入 innerHTML 的动态文本）。 */
function esc(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/* ------------------------------------------------------------------ *
 * 字符串化十进制减法（§4.1.1：金额 / 数量禁用 Number 参与展示，避免精度丢失）
 * 纯字符串实现；仅对「单个数字字符」做 0–9 的整数运算（精确、无精度损失）。
 * ------------------------------------------------------------------ */

/** 解析十进制串 → {neg, int, frac}；空值视为 0；非法返回 null。 */
function parseDec(value) {
  if (value === null || value === undefined) return { neg: false, int: '0', frac: '' };
  const s = String(value).trim();
  if (s === '') return { neg: false, int: '0', frac: '' };
  if (!/^-?\d+(\.\d+)?$/.test(s)) return null;
  const neg = s.charAt(0) === '-';
  const body = neg ? s.slice(1) : s;
  const dot = body.indexOf('.');
  return { neg: neg, int: dot === -1 ? body : body.slice(0, dot), frac: dot === -1 ? '' : body.slice(dot + 1) };
}

/** 去前导零（至少保留一位）。 */
function stripZeros(digits) { const t = digits.replace(/^0+/, ''); return t === '' ? '0' : t; }

/** 无符号整数串比较：a>b→1 / a<b→-1 / 相等→0。 */
function cmpDigits(a, b) {
  const x = stripZeros(a), y = stripZeros(b);
  if (x.length !== y.length) return x.length > y.length ? 1 : -1;
  return x === y ? 0 : (x > y ? 1 : -1);
}

/** 无符号整数串相加。 */
function addDigits(a, b) {
  let i = a.length - 1, j = b.length - 1, carry = 0, out = '';
  while (i >= 0 || j >= 0 || carry) {
    const sum = (i >= 0 ? +a.charAt(i) : 0) + (j >= 0 ? +b.charAt(j) : 0) + carry;
    out = (sum % 10) + out;
    carry = sum >= 10 ? 1 : 0;
    i--; j--;
  }
  return stripZeros(out);
}

/** 无符号整数串相减（要求 a >= b）。 */
function subDigits(a, b) {
  let i = a.length - 1, j = b.length - 1, borrow = 0, out = '';
  while (i >= 0) {
    let d = (+a.charAt(i)) - borrow - (j >= 0 ? +b.charAt(j) : 0);
    if (d < 0) { d += 10; borrow = 1; } else { borrow = 0; }
    out = d + out;
    i--; j--;
  }
  return stripZeros(out);
}

/**
 * 字符串化十进制减法 a - b（不经 Number 解析数量，避免精度丢失）。
 * 结果对齐到两操作数较大者的小数位并去除尾零；空值视作 0；非法入参返回 ''（调用方兜底）。
 */
function decSub(a, b) {
  const pa = parseDec(a), pb = parseDec(b);
  if (!pa || !pb) return '';
  const scale = Math.max(pa.frac.length, pb.frac.length);
  const amag = pa.int + pa.frac.padEnd(scale, '0');
  const bmag = pb.int + pb.frac.padEnd(scale, '0');
  let negOut, mag;
  if (pa.neg === pb.neg) {
    const c = cmpDigits(amag, bmag);
    if (c === 0) { negOut = false; mag = '0'; }
    else if (pa.neg) { negOut = c > 0; mag = c > 0 ? subDigits(amag, bmag) : subDigits(bmag, amag); }
    else { negOut = c < 0; mag = c > 0 ? subDigits(amag, bmag) : subDigits(bmag, amag); }
  } else {
    negOut = pa.neg; mag = addDigits(amag, bmag);
  }
  if (mag === '0') negOut = false;
  let intPart, fracPart;
  if (scale === 0) { intPart = mag; fracPart = ''; }
  else {
    const padded = mag.padStart(scale + 1, '0');
    intPart = padded.slice(0, padded.length - scale);
    fracPart = padded.slice(padded.length - scale).replace(/0+$/, '');
  }
  return (negOut ? '-' : '') + intPart + (fracPart ? '.' + fracPart : '');
}

HG.format = { decimal, fixed, money, qty, pct, date, dateTime, esc, decSub };
export default HG.format;
