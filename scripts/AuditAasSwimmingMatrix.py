"""Run the bounded swimming differential over independent geometry, layer and setting profiles."""
import os
import subprocess
import sys

profiles=[
 ('open',{'OPEN_ONLY':'1'}),
 ('flat',{}),
 ('planes',{'PLANES':'1','SEED':'5535002'}),
 ('vertical-layer',{'PLANES':'1','FLUID_Z':'100','SEED':'5535003'}),
 ('horizontal-layer-area-fluids',{'PLANES':'1','FLUID_X':'0','AREA_CONTENTS':'7','SEED':'5535004'}),
 ('zero-water-settings',{'PLANES':'1','phys_waterfriction':'0','phys_watergravity':'0','phys_maxswimvelocity':'0','phys_swimaccelerate':'0'}),
 ('fractional-water-settings',{'PLANES':'1','FLUID_Z':'100','phys_waterfriction':'3.75','phys_watergravity':'725.125','phys_maxswimvelocity':'333.75','phys_swimaccelerate':'7.25'}),
 ('maximum-water-settings',{'PLANES':'1','phys_waterfriction':'100000','phys_watergravity':'100000','phys_maxswimvelocity':'100000','phys_swimaccelerate':'100000'}),
]
for name,variables in profiles:
 print('PROFILE',name,flush=True)
 subprocess.run([sys.executable,'scripts/AuditAasSwimming.py'],env={**os.environ,**variables},check=True)
print(f'PASS: {len(profiles)*int(os.getenv("COUNT","10000"))} native/production comparisons across eight controlled profiles',flush=True)
