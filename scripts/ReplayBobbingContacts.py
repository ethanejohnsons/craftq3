"""Replay ignored live contact captures through the authored native full-goal observer."""
import re,subprocess
from pathlib import Path
for profile in ('retail','modern','matching-modern'):
 s=Path(f'/tmp/craftq3-bobbing-entry-retries/{profile}-contact.log').read_text()
 line=next(x for x in s.splitlines() if x.startswith('BOT CHECKPOINT'))
 def v(section,key):
  m=re.search(key+r'=Vec3\[x=([^,]+), y=([^,]+), z=([^\]]+)\]',section);return ' '.join(m.groups())
 def n(section,key):return re.search(key+r'=([^,;\]]+)',section)[1]
 init=line.split('input=MovementInit[')[1].split(']; goal=')[0]
 goal=line.split('goal=Goal[')[1].split(']; policy=')[0]
 history=line.split('history=History[')[1].split(']; effectiveFlags=')[0]
 avoid=line.split('avoidance=ReachAvoidance[')[1].split(']')[0]
 contact=next(x for x in s.splitlines() if x.startswith('PLATFORM CONTACT'))
 ent=int(re.search(r'entity=(\d+)',contact)[1]);fraction=re.search(r'fraction=([^,]+)',contact)[1]
 cmd=[f'frame {n(line,"time")}']
 for x in s.splitlines():
  if x.startswith('MOVER '):
   m=re.match(r'MOVER (\d+) model(\d+) origin (\S+) (\S+) (\S+) solid(\d+)',x)
   a=m.groups();cmd.append('mover '+' '.join(a[:5])+' 4 '+a[5])
 cmd+=['init '+' '.join([v(init,'origin'),v(init,'velocity'),v(init,'viewOffset'),n(init,'entity'),n(init,'client'),n(init,'thinkTime'),n(init,'presenceType'),v(init,'viewAngles'),n(init,'moveFlags')]),'history '+' '.join([n(history,x) for x in ('area','lastArea','lastGoalArea','lastReachability','reachArea')]+[n(init,'moveFlags'),n(history,'jumpReachability'),n(history,'reachDeadline'),v(history,'lastOrigin')]),'avoid '+' '.join(n(avoid,x) for x in ('reachability','expiresAt','tries')),f'groundhit {ent} {fraction} 0',f'platformhit {ent} .5 0','traceverbose 1','goal '+' '.join([v(goal,'origin'),n(goal,'area'),n(line.split('policy=')[1],'travelFlags'),'.25 -.5 .125 123 8192'])]
 text='\n'.join(cmd)+'\n'
 Path(f'.tools/bobbing-goal-oracle/{profile}-live-input.txt').write_text(text)
 p=subprocess.run(['.tools/bobbing-goal-oracle/probe','run/craftq3/games/baseq3/pak0.pk3','q3dm19'],input=text,text=True,capture_output=True)
 Path(f'.tools/bobbing-goal-oracle/{profile}-live-output.log').write_text(p.stdout+'\nSTDERR\n'+p.stderr)
 print(profile,p.returncode);print('\n'.join(x for x in p.stdout.splitlines() if x.startswith(('RESULT','STATE','EA'))))
