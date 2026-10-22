/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

export const TARGET_LANGUAGES = Object.freeze({
  eng: 'English', spa: 'Spanish', hin: 'Hindi', kor: 'Korean', jpn: 'Japanese', fra: 'French',
});

const latin = [
  ['eng', "the and you i'm i you're it's is are was to of in on that this with your my me we don't can't won't what when just all now like love know never get got gonna wanna be hello goodbye", ''],
  ['spa', 'el los las que y en un una es por con para mi tu te yo como pero más quiero eres estoy está todo nada cuando porque sin mí qué contigo corazón amor vida soy aquí hola', 'ñ¿¡'],
  ['fra', "le les des et je il elle nous vous est qui pas ne dans pour avec mon ma mes ton ta moi toi c'est j'ai suis plus mais sur au du ce tout bonjour", 'œêûî'],
  ['por', 'os as um uma não é eu você meu minha com para na do da em mais mas seu sua tudo coração quando tão também pra só estou vou', 'ãõ'],
  ['deu', 'der die das und ich du nicht ist ein eine mit mich mir dich dir wir auf für sie zu den dem im auch noch nur wenn aber mein dein kein bin hab war', 'ßä'],
  ['ita', 'il lo gli che di e è non per con mi ti sono sei io del della nel ma più come cosa amore cuore perché questo quando ancora anche sempre', 'ì'],
  ['cat', 'els les que de i és per amb em et jo del dels al als però més tot quan ara sóc ets meu teu molt aquest perquè', '·'],
  ['nld', 'de het een en ik je jij niet is van dat die op met voor mijn jouw zijn wat maar ook nog wel als er naar bij hou ben heb ze', 'ĳ'],
  ['tur', 've bir bu ben sen ne ki için gibi çok beni seni var yok değil mi ama her şey aşk sana bana benim senin daha', 'ğış'],
].map(([code, words, marks]) => ({ code, words: new Set(words.split(' ')), marks }));

function detect(line) {
  if (/[\u1100-\u11ff\u3130-\u318f\uac00-\ud7a3]/u.test(line)) return 'kor';
  if (/[\u3040-\u30ff]/u.test(line)) return 'jpn';
  if (/[\u4e00-\u9fff]/u.test(line)) return 'zho';
  if (/[\u0400-\u04ff]/u.test(line)) return 'rus';
  if (/[\u0370-\u03ff]/u.test(line)) return 'ell';
  if (/[\u0600-\u06ff]/u.test(line)) return 'ara';
  if (/[\u0900-\u097f]/u.test(line)) return 'hin';
  const text = line.toLowerCase().replaceAll('’', "'");
  const words = text.match(/[\p{L}'·]+/gu) ?? [];
  const scores = latin.map(language => ({ code: language.code,
    score: words.filter(word => language.words.has(word)).length +
      [...language.marks].filter(mark => text.includes(mark)).length * 2,
  })).sort((a, b) => b.score - a.score);
  return scores[0].score > scores[1].score ? scores[0].code : '';
}

export function translationMask(lines, target) {
  const codes = lines.map(detect);
  if (codes.includes('jpn')) codes.forEach((code, index) => { if (code === 'zho') codes[index] = 'jpn'; });
  const counts = new Map();
  for (const code of codes) if (code) counts.set(code, (counts.get(code) ?? 0) + 1);
  const source = [...counts].sort((a, b) => b[1] - a[1])[0]?.[0];
  return lines.map((line, index) => /\p{L}/u.test(line) && codes[index] !== target &&
    !(codes[index] === '' && source === target && latin.some(language => language.code === source) && /\p{Script=Latin}/u.test(line)));
}
