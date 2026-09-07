"""Offline structural, attribution and complete snapshot/database agreement checks."""
import gzip, hashlib, json, re, sqlite3, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT.parents[1] / 'src/main/assets/learning'

class LexiconTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.db = sqlite3.connect(f'file:{(ASSETS / "lexicon.sqlite3").as_posix()}?mode=ro', uri=True)
  with gzip.open(ROOT/'selected.jsonl.gz','rt',encoding='utf-8') as file:
   cls.records = [json.loads(line) for line in file]
 @classmethod
 def tearDownClass(cls): cls.db.close()
 def test_manifest_integrity(self):
  report=json.loads((ASSETS/'manifest.json').read_text())
  self.assertEqual(hashlib.sha256((ASSETS/'lexicon.sqlite3').read_bytes()).hexdigest(),report['sha256'])
  self.assertEqual(len(self.records),30000)
  self.assertEqual(self.db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
 def test_snapshot_and_attribution(self):
  seen=set()
  for item in self.records:
   word=item['word']; self.assertNotIn(word,seen); seen.add(word)
   self.assertEqual(item,json.loads(self.db.execute('SELECT payload FROM entries WHERE word=?',(word,)).fetchone()[0]))
   self.assertTrue(item['meanings']); self.assertLessEqual(len(item['examples']),3)
   allowed={word}|{f.split(':',1)[1].lower() for f in item['forms'] if f.split(':',1)[0] in ('p','d','i','3','r','t','s')}
   self.assertEqual(len(item['examples']),len({e['english'].lower() for e in item['examples']}))
   for ex in item['examples']:
    self.assertTrue(allowed.intersection(re.findall(r"[a-z]+(?:[-'][a-z]+)*",ex['english'].lower())))
    self.assertGreater(ex['englishId'],0); self.assertGreater(ex['chineseId'],0)
    self.assertNotIn(ex['englishAuthor'],('',r'\N')); self.assertNotIn(ex['chineseAuthor'],('',r'\N'))
    self.assertEqual(ex['license'],'CC-BY-2.0-FR')
 def test_common_words_and_inflections(self):
  for word in ('learn','run','bank','book','set','hello','apple','study'):
   item=json.loads(self.db.execute('SELECT payload FROM entries WHERE word=?',(word,)).fetchone()[0])
   self.assertTrue(item['meanings'])
  self.assertIn(('run',), self.db.execute('SELECT word FROM forms WHERE form="ran"').fetchall())
  self.assertIsNone(self.db.execute('SELECT word FROM entries WHERE word="not-a-real-word-xyz"').fetchone())

if __name__=='__main__': unittest.main()
