from pathlib import Path
import argparse,subprocess,json,hashlib,os,zipfile
root=Path(__file__).resolve().parents[1];repo='mihile/better-sunlit';filename='better-sunlit.jar'
def run(a,**kw):return subprocess.run(list(map(str,a)),check=True,**kw)
def output(a):return subprocess.check_output(list(map(str,a)),text=True,encoding='utf8').strip()
def main():
 p=argparse.ArgumentParser();p.add_argument('--side',choices=['client','server'],required=True);p.add_argument('--changes',type=Path,default=root/'releases/20261108.1.json');p.add_argument('--keys',type=Path,default=Path.home()/'.codex/secrets/better-sunlit');p.add_argument('--prepare-only',action='store_true');p.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')));a=p.parse_args();build=root/'build'/a.side;report=json.loads((build/'build-report.json').read_text());changes=json.loads(a.changes.read_text(encoding='utf8'));jar=build/filename
 assert report['build']==changes['build'] and report['label']==changes['label'] and report['side']==a.side
 assert hashlib.sha256(jar.read_bytes()).hexdigest()==report['sha256'];assert 0<len(changes['features'])<=40
 key=a.keys/(a.side+'.pk8');assert key.exists() and not key.resolve().is_relative_to(root.resolve())
 tag=a.side+'-build-'+changes['label'];release=build/'release';release.mkdir(exist_ok=True);(release/filename).write_bytes(jar.read_bytes())
 props={'schema':'1','mod':'better_sunlit','channel':a.side,'pack':'sunlit-valley','version':'1.0.0','filename':filename,'build':str(report['build']),'label':report['label'],'title':changes['title'],'tag':tag,'url':f'https://github.com/{repo}/releases/download/{tag}/{filename}','size':str(report['size']),'sha256':report['sha256']}
 for i,f in enumerate(changes['features']):
  assert 0<len(f['title'])<=80 and ' :: ' not in f['title'];note=f['title']+' :: '+f['detail'];assert len(note)<=1200 and not any(ord(c)<32 for c in note);props['notes.'+str(i)]=note
 def escape(s):return str(s).replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')
 payload=build/'payload.properties';payload.write_text(''.join(k+'='+escape(v)+'\n' for k,v in props.items()),encoding='utf8');tools=build/'maintainer-tools';tools.mkdir(exist_ok=True);java=a.java_home/'bin/java.exe';javac=a.java_home/'bin/javac.exe'
 run([javac,'--release','17','-encoding','UTF-8','-cp',jar,'-d',tools,root/'tools/SignRelease.java',root/'tools/VerifyRelease.java'])
 run([java,'-cp',tools,'SignRelease','sign',key,payload,release/'update.properties']);run([java,'-cp',str(tools)+os.pathsep+str(jar),'VerifyRelease',release/'update.properties',release/filename])
 (release/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256((release/n).read_bytes()).hexdigest()+'  '+n+'\n' for n in [filename,'update.properties']),encoding='utf8')
 notes='# '+changes['title']+' · '+a.side+'\n\n'+''.join('<details>\n<summary>'+f['title']+'</summary>\n\n'+f['detail']+'\n\n</details>\n\n' for f in changes['features']);(release/'notes.md').write_text(notes,encoding='utf8')
 if a.prepare_only:return
 branch=output(['git','-C',root,'branch','--show-current']);assert branch==a.side,'Publish from matching branch';assert not output(['git','-C',root,'status','--porcelain']),'Commit source before release'
 sha=output(['git','-C',root,'rev-parse','HEAD']);assert output(['git','-C',root,'rev-parse','origin/'+a.side])==sha,'Push reviewed source before release'
 existing=json.loads(output(['gh','api',f'repos/{repo}/releases?per_page=100']))
 for r in existing:
  if r['tag_name'].startswith(a.side+'-build-') and not r['draft']:
   old=r['tag_name'][len(a.side+'-build-'):].split('.');old_number=int(''.join(old[:3])+old[3].zfill(2));assert report['build']>old_number,'Build must increase'
 run(['gh','release','create',tag,'--repo',repo,'--target',sha,'--title',changes['title']+' · '+a.side,'--notes-file',release/'notes.md','--latest=false',release/filename,release/'update.properties',release/'SHA256SUMS.txt'])
 # Advance only this channel's feed, after its immutable release assets exist.
 feed=root/'feed';feed.mkdir(exist_ok=True);(feed/'update.properties').write_bytes((release/'update.properties').read_bytes());run(['git','-C',root,'add','feed/update.properties']);run(['git','-C',root,'commit','-m','Publish '+a.side+' feed '+changes['label']]);run(['git','-C',root,'push','origin',a.side])
 check=build/'public-verification';check.mkdir(exist_ok=True);run(['gh','release','download',tag,'--repo',repo,'--dir',check,'--clobber','--pattern',filename,'--pattern','update.properties']);run([java,'-cp',str(tools)+os.pathsep+str(jar),'VerifyRelease',check/'update.properties',check/filename]);assert (check/filename).read_bytes()==jar.read_bytes()
 print('Published and re-downloaded:',repo,tag)
if __name__=='__main__':main()
