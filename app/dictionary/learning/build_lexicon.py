"""Deterministic offline study lexicon. Download is deliberately separate from generation.
Refresh: --sources DIR (pinned upstream dumps); rebuild: --snapshot selected.jsonl.gz.
Outputs contain public lexicon content only, never device data.
"""
import argparse, bz2, csv, gzip, hashlib, json, re, sqlite3
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parents[1] / 'src/main/assets/learning'
HASHES = {
 'ecdict.csv':'1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf',
 'eng.tsv.bz2':'db035e9dbe35d6fe86410bbf16dd3ce735a3d4b74f92b4ee5482bba11cb970bc',
 'cmn.tsv.bz2':'50e966ec563aebff2af0629e6a127cf41ad2b0a71801336086ec1e7a318bb082',
 'links.tsv.bz2':'0833aea5473c24040a1d34d080a57250152f3129a049730276f395ba6482ff09',
}
WORD = re.compile(r"[a-z]+(?:[-'][a-z]+)*")
REJECT = re.compile(r'\b(?:fuck|shit|rape|porn|suicide|kill|murder|nazi)\w*\b|强奸|自杀|色情|杀死', re.I)
def lines(file):
 with bz2.open(file, 'rt', encoding='utf-8') as stream:
  yield from csv.reader(stream, delimiter='\t', quoting=csv.QUOTE_NONE)
def prepare(root):
 for name,digest in HASHES.items():
  assert hashlib.sha256((root/name).read_bytes()).hexdigest() == digest, name
 def rank(row):
  ranks = [int(row.get(k) or 0) for k in ('bnc','frq')]
  return min([r for r in ranks if r>0] or [10**9]), row['word'].lower()
 with (root/'ecdict.csv').open(encoding='utf-8') as stream:
  candidates = sorted((r for r in csv.DictReader(stream) if WORD.fullmatch(r['word'].lower()) and r['translation'].strip()), key=rank)
 entries = {}
 for row in candidates:
  word=row['word'].lower()
  if word in entries: continue
  meanings = list(dict.fromkeys(s.strip() for s in row['translation'].split('\\n') if s.strip()))
  forms = [f for f in row['exchange'].split('/') if ':' in f]
  entries[word] = dict(word=word, phonetic=row['phonetic'], meanings=meanings, definition=row['definition'].replace('\\n','\n'), forms=forms, examples=[])
  if len(entries)==30000: break
 assert len(entries)==30000
 aliases=defaultdict(set)
 for word,item in entries.items():
  aliases[word].add(word)
  for form in item['forms']:
   kind,value=form.split(':',1)
   if kind in ('p','d','i','3','r','t','s') and WORD.fullmatch(value.lower()): aliases[value.lower()].add(word)
 links=defaultdict(list)
 for en,zh in lines(root/'links.tsv.bz2'): links[int(en)].append(int(zh))
 zhids={i for values in links.values() for i in values}
 chinese={int(r[0]):r for r in lines(root/'cmn.tsv.bz2') if int(r[0]) in zhids and len(r)>=4 and r[3] not in ('','\\N') and not REJECT.search(r[2])}
 examples=defaultdict(list)
 for row in lines(root/'eng.tsv.bz2'):
  if len(row)<4 or row[3] in ('','\\N') or int(row[0]) not in links: continue
  en=row[2]; tokens=WORD.findall(en.lower())
  if not 5<=len(tokens)<=20 or len(en)>180 or REJECT.search(en): continue
  targets=set().union(*(aliases.get(t,set()) for t in tokens))
  if not targets: continue
  for zhId in sorted(links[int(row[0])]):
   zh=chinese.get(zhId)
   if not zh or len(zh[2])>100: continue
   example=dict(english=en,chinese=zh[2],englishId=int(row[0]),chineseId=zhId,englishAuthor=row[3],chineseAuthor=zh[3],license='CC-BY-2.0-FR')
   for word in targets: examples[word].append(example)
 for word,item in entries.items():
  seen=set()
  for ex in sorted(examples[word],key=lambda e:(abs(len(WORD.findall(e['english']))-9),e['englishId'],e['chineseId'])):
   if ex['english'].lower() in seen: continue
   item['examples'].append(ex); seen.add(ex['english'].lower())
   if len(item['examples'])==3: break
 return sorted(entries.values(),key=lambda r:r['word'])
def build(records):
 OUTPUT.mkdir(parents=True,exist_ok=True)
 path=OUTPUT/'lexicon.sqlite3'
 # A generator may replace only its exact owned build artifact.
 if path.exists(): path.unlink()
 db=sqlite3.connect(path)
 db.executescript('PRAGMA page_size=4096; CREATE TABLE entries(word TEXT PRIMARY KEY, payload TEXT NOT NULL); CREATE TABLE forms(form TEXT NOT NULL, word TEXT NOT NULL, PRIMARY KEY(form,word));')
 for record in records:
  word=record['word']
  assert 1<=len(record['meanings']) and len(record['examples'])<=3
  db.execute('INSERT INTO entries VALUES (?,?)',(word,json.dumps(record,ensure_ascii=False,separators=(',',':'))))
  for form in record['forms']:
   kind,value=form.split(':',1)
   if kind in ('p','d','i','3','r','t','s') and WORD.fullmatch(value.lower()): db.execute('INSERT OR IGNORE INTO forms VALUES (?,?)',(value.lower(),word))
 db.commit(); db.execute('VACUUM'); db.close()
 report=dict(version=1,ecdictCommit='bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b',tatoebaDate='2026-09-05',upstreamSha256=HASHES,words=len(records),wordsWithExamples=sum(bool(r['examples']) for r in records),examples=sum(len(r['examples']) for r in records),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size)
 (OUTPUT/'manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
 print(json.dumps(report),flush=True)
if __name__=='__main__':
 parser=argparse.ArgumentParser(); parser.add_argument('--sources',type=Path); parser.add_argument('--snapshot',type=Path)
 args=parser.parse_args()
 if args.sources:
  records=prepare(args.sources)
  snapshot=('\n'.join(json.dumps(r,ensure_ascii=False,separators=(',',':')) for r in records)+'\n').encode()
  (HERE/'selected.jsonl.gz').write_bytes(gzip.compress(snapshot,mtime=0))
 else:
  with gzip.open(args.snapshot or HERE/'selected.jsonl.gz','rt',encoding='utf-8') as source: records=[json.loads(line) for line in source]
 build(records)
