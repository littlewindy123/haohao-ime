"""Pinned public ECDICT -> compact shared wordbook snapshot. No runtime downloads."""
import argparse, csv, gzip, hashlib, io, json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMIT = 'bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b'
SHA256 = '1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf'
WORD = re.compile(r"[a-z]+(?:['-][a-z]+)*")

def select(source):
    assert hashlib.sha256(source.read_bytes()).hexdigest() == SHA256, 'Source hash mismatch'
    selected = {}
    rejected = 0
    with source.open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            word = row['word'].strip().lower()
            if not WORD.fullmatch(word) or len(word)>32: continue
            translation = row['translation'].replace('\\r','\r').replace('\\n','\n').replace('\\t','\t')
            meanings = [' '.join(s.split()) for s in translation.splitlines() if s.strip()]
            meaning = next((s for s in meanings if len(s)<=512 and not any(ord(c)<32 for c in s) and re.search(r'[\u3400-\u9fff]',s)),None)
            if meaning is None: rejected += 1; continue
            ranks = [int(row[k]) for k in ('bnc','frq') if row[k].isdigit() and int(row[k])>0]
            item = (min(ranks or [10**9]),word,meaning,row['phonetic'].strip() or None,set(row['tag'].split()))
            if word not in selected or item[0] < selected[word][0]: selected[word] = item
    ordered=sorted(selected.values(),key=lambda x:(x[0],x[1]))
    ids={'basic':[x[1] for x in ordered[:1000]],'cet4':[x[1] for x in ordered if 'cet4' in x[4]],'cet6':[x[1] for x in ordered if 'cet6' in x[4]]}
    used=set().union(*map(set,ids.values()))
    names={'basic':'基础高频1000','cet4':'四级参考词汇','cet6':'六级参考词汇'}
    result={'version':1,'sourceCommit':COMMIT,'words':[{'english':w,'chinese':selected[w][2],'phonetic':selected[w][3]} for w in sorted(used)],'books':[{'id':k,'name':names[k],'words':v} for k,v in ids.items()]}
    assert len(ids['basic'])==1000 and ids['cet4'] and ids['cet6']
    return result,rejected

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--source',type=Path,required=True); args=parser.parse_args()
    result,rejected=select(args.source)
    raw=json.dumps(result,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    buffer=io.BytesIO()
    with gzip.GzipFile(fileobj=buffer,mode='wb',filename='',mtime=0) as archive:
        archive.write(raw)
    packed=buffer.getvalue()
    output=ROOT/'src/main/assets/learning/wordbooks.bin'; output.write_bytes(packed)
    manifest={'version':1,'sourceCommit':COMMIT,'sourceSha256':SHA256,'sha256':hashlib.sha256(packed).hexdigest(),'bytes':len(packed),'uniqueWords':len(result['words']),'books':{b['id']:len(b['words']) for b in result['books']},'rejectedDefinitions':rejected}
    (output.parent/'wordbooks-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(manifest))
if __name__=='__main__': main()
