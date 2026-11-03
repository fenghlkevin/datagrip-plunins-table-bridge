#!/usr/bin/env python3
"""Annotated walkthrough from genuine production Swing UI captures; no fabricated execution result."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import math, shutil, json
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'build/sdk-smoke'; OUT=ROOT/'build/demo-frames'; MEDIA=ROOT/'docs/media'
OUT.mkdir(parents=True,exist_ok=True);MEDIA.mkdir(parents=True,exist_ok=True)
FONT='/System/Library/Fonts/Supplemental/Arial Unicode.ttf'
BOLD='/System/Library/Fonts/Supplemental/Arial Bold.ttf'
def font(size,bold=False):return ImageFont.truetype(BOLD if bold else FONT,size)
SCENES=[
 ('Copy. Paste. Review.','Copy objects in the database tree, then paste onto the destination.','ui-wizard-objects.png','⌘C / Ctrl+C  →  ⌘V / Ctrl+V',None),
 ('01  Confirm the destination','Confirm the source and destination using your DataGrip connections.','ui-wizard-connections.png','Source → destination',(0.68,0.30)),
 ('02  Choose your objects','Select what to copy. Searching preserves your selected objects.','ui-wizard-objects.png','Tables · views · routines',(0.11,0.51)),
 ('03  Configure each object','Use Advanced to rename objects and choose structure, data, or both.','ui-wizard-advanced.png','Automatic or advanced',(0.60,0.50)),
 ('04  Review full definitions','Review indexes, collations and triggers in the editable target definition.','ui-full-structure.png','Source and destination SQL',(0.76,0.24)),
 ('05  Check the transfer plan','Review objects, target names, copy content and conversion notes.','ui-wizard-review.png','Summary before execution',(0.52,0.37)),
 ('06  Read the SQL before starting','Inspect the generated SQL. Confirm the plan before starting.','ui-wizard-sql.png','Review → confirm → start',(0.90,0.94)),
]
FPS=12;SECONDS=8
posters=[];gifframes=[]
for scene,(title,caption,name,hint,focus) in enumerate(SCENES):
    source=Image.open(SRC/name).convert('RGB')
    base=Image.new('RGB',(1280,800),(14,17,24));d=ImageDraw.Draw(base)
    d.text((38,24),'TableBridge',font=font(24,True),fill='#F3F6FC')
    d.text((232,30),'DataGrip 2026.2  /  0.3.1-alpha',font=font(15),fill='#98A5B9')
    d.rounded_rectangle((923,22,1242,57),radius=10,fill='#202C42')
    d.text((939,30),'SDK sample metadata · UI preview',font=font(14),fill='#BFD3FF')
    d.line((38,75,1242,75),fill='#30394B')
    d.text((38,105),title.split('  ')[0] if scene else 'Copy → Paste',font=font(22,True),fill='#80AAFF')
    short=title.split('  ')[-1] if scene else 'Database objects'
    words=short.split(); lines=[];line=''
    for word in words:
        candidate=(line+' '+word).strip()
        if d.textlength(candidate,font=font(28,True))>230 and line:lines.append(line);line=word
        else:line=candidate
    if line:lines.append(line)
    for j,t in enumerate(lines):d.text((38,151+j*38),t,font=font(28,True),fill='#F1F5FD')
    d.text((38,330),'MySQL ↔ PostgreSQL',font=font(17),fill='#BEC9DA')
    d.text((38,364),'Object copy',font=font(16),fill='#92A0B7')
    d.text((38,393),'SQL review',font=font(16),fill='#92A0B7')
    d.text((38,422),'Recovery & sync',font=font(16),fill='#92A0B7')
    d.text((38,611),f'{scene+1:02d} / {len(SCENES):02d}',font=font(19),fill='#7DA7FF')
    scale=min(929/source.width,594/source.height)
    size=(round(source.width*scale),round(source.height*scale));pic=source.resize(size,Image.Resampling.LANCZOS)
    x=300+(930-size[0])//2;y=90+(594-size[1])//2
    d.rounded_rectangle((x-2,y-2,x+size[0]+2,y+size[1]+2),radius=7,outline='#40506A',width=2)
    base.paste(pic,(x,y))
    d.rounded_rectangle((32,701,1248,783),radius=14,fill='#1C2535')
    d.text((51,711),hint,font=font(22,True),fill='#F2F6FF')
    d.text((51,747),caption,font=font(20),fill='#C5D4EB')
    for frame in range(FPS*SECONDS):
        image=base.copy();draw=ImageDraw.Draw(image)
        if focus and frame>=FPS:
            px=x+size[0]*focus[0];py=y+size[1]*focus[1]
            r=14+3*math.sin(frame/FPS*2.8)
            draw.ellipse((px-r,py-r,px+r,py+r),outline='#73A3FF',width=3)
            draw.polygon([(px,py),(px+5,py+20),(px+10,py+13),(px+19,py+13)],fill='white',outline='#0A1428')
        progress=(scene*FPS*SECONDS+frame+1)/(len(SCENES)*FPS*SECONDS)
        draw.rectangle((0,797,round(1280*progress),799),fill='#578FFF')
        image.save(OUT/f'frame-{scene*FPS*SECONDS+frame:05d}.jpg',quality=90)
        if frame==FPS*3:posters.append(image)
        if scene in (1,2,3,5,6) and frame%(FPS//2)==0:gifframes.append(image.resize((640,400),Image.Resampling.LANCZOS))
cover=posters[2].copy();d=ImageDraw.Draw(cover);d.rounded_rectangle((678,300,816,386),radius=20,fill='#3478F6');d.polygon([(726,320),(726,366),(766,343)],fill='white');cover.save(MEDIA/'tablebridge-video-cover.png',optimize=True)
for name,file in [('objects.png','ui-wizard-objects.png'),('sql-review.png','ui-wizard-sql.png'),('advanced.png','ui-wizard-advanced.png'),('full-structure.png','ui-full-structure.png')]:shutil.copyfile(SRC/file,MEDIA/name)
gifframes[0].save(MEDIA/'tablebridge-preview.gif',save_all=True,append_images=gifframes[1:],duration=500,loop=0,optimize=True)
(ROOT/'build/demo-storyboard.png').parent.mkdir(exist_ok=True)
contact=Image.new('RGB',(1280,1200),'#10151D')
for j,img in enumerate(posters):contact.paste(img.resize((640,400)),((j%2)*640,(j//2)*400)) if j<6 else None
contact.save(ROOT/'build/demo-storyboard.png')
(MEDIA/'manifest.json').write_text(json.dumps({'version':'0.3.1-alpha','durationSeconds':len(SCENES)*SECONDS,'fps':FPS,'resolution':[1280,800],'source':'Production plugin Swing UI captured in isolated DataGrip 2026.2 SDK, annotated sample-metadata preview; no database execution','chapters':[{'startSeconds':i*SECONDS,'title':s[0],'caption':s[1]} for i,s in enumerate(SCENES)]},ensure_ascii=False,indent=2)+'\n')
print(f'Prepared {len(SCENES)*FPS*SECONDS} frames, cover, screenshots and GIF')
