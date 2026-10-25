"""Render the five-page PDF as a captioned, locally narrated 1080p MP4.

Requires Python/Pillow, macOS say, Poppler pdftoppm, ffmpeg and ffprobe.
Usage: python3 submission/build_video.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'tmp/video'
OUT = ROOT / 'output/video'
WORK.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)

def run(args):
    subprocess.run([str(x) for x in args], check=True, stdout=subprocess.DEVNULL)

def duration(path):
    return float(subprocess.check_output(['ffprobe','-v','error','-show_entries',
        'format=duration','-of','default=noprint_wrappers=1:nokey=1',str(path)],text=True))

def timestamp(value):
    ms=round(value*1000)
    return f'{ms//3600000:02}:{ms//60000%60:02}:{ms//1000%60:02},{ms%1000:03}'

def font(size, bold=False):
    candidate=Path('/System/Library/Fonts/Supplemental') / ('Arial Bold.ttf' if bold else 'Arial.ttf')
    return ImageFont.truetype(str(candidate),size)

def wrap(draw,text,width,style):
    lines=[]; current=''
    for word in text.split():
        test=(current+' '+word).strip()
        if draw.textlength(test,font=style)>width and current:
            lines.append(current);current=word
        else:current=test
    if current:lines.append(current)
    return lines

script=json.loads((ROOT/'submission/video-narration.json').read_text())
pdf=ROOT/'output/pdf/fit-check-pitch.pdf'
run(['pdftoppm','-scale-to-x','1648','-scale-to-y','927','-png',pdf,WORK/'slide'])
pages=[Image.open(WORK/f'slide-{i}.png').convert('RGB') for i in range(1,6)]
captions=[]; manifest=[]; elapsed=0

for index,item in enumerate(script):
    number=index+1
    stem=WORK/f'part-{number:02}'
    spoken=item['text'].replace('LiteRT','Light R T')
    speech=stem.with_suffix('.txt');speech.write_text(spoken)
    audio=stem.with_suffix('.aiff')
    run(['say','-v','Samantha','-r','170','-f',speech,'-o',audio])
    seconds=duration(audio)+0.65
    frame=Image.new('RGB',(1920,1080),'#F6F3EA')
    frame.paste(pages[item['slide']-1],(136,0))
    draw=ImageDraw.Draw(frame)
    draw.rectangle((0,938,1920,1080),fill='#20231E')
    draw.rectangle((0,933,round(1920*number/len(script)),937),fill='#DBEF8F')
    label=f'NARRATED PITCH  /  SLIDE {item["slide"]} OF 5'
    draw.text((96,949),label,font=font(17,True),fill='#DBEF8F')
    draw.text((1090,949),'SYNTHETIC NARRATION  /  NOT A LIVE APP RECORDING',font=font(16),fill='#CFD0C7')
    style=font(34)
    lines=wrap(draw,item['text'],1728,style)
    assert len(lines)<=2, (number,lines)
    for row,line in enumerate(lines):
        draw.text((96,982+row*42),line,font=style,fill='#F6F3EA')
    image=stem.with_suffix('.png');frame.save(image)
    clip=stem.with_suffix('.mp4')
    filters='fps=24,format=yuv420p'
    if index==0 or script[index-1]['slide']!=item['slide']:
        filters+=',fade=t=in:st=0:d=0.25:color=0xF6F3EA'
    if index==len(script)-1 or script[index+1]['slide']!=item['slide']:
        filters+=f',fade=t=out:st={seconds-0.25:.3f}:d=0.25:color=0xF6F3EA'
    run(['ffmpeg','-hide_banner','-loglevel','error','-y','-loop','1','-framerate','24',
        '-i',image,'-i',audio,'-t',f'{seconds:.3f}','-vf',filters,
        '-af','adelay=100|100,apad,loudnorm=I=-17:TP=-1.5:LRA=7',
        '-c:v','libx264','-preset','veryfast','-tune','stillimage','-crf','21',
        '-pix_fmt','yuv420p','-profile:v','high',
        '-c:a','aac','-b:a','160k','-ar','48000','-ac','2',clip])
    actual=duration(clip)
    captions.append(f'{number}\n{timestamp(elapsed+0.1)} --> {timestamp(elapsed+actual-0.4)}\n'+
                    '\n'.join(wrap(draw,item['text'],1728,style))+'\n')
    manifest.append({**item,'start_seconds':round(elapsed,3),'duration_seconds':actual})
    elapsed+=actual
    print(f'Rendered narration {number}/{len(script)}',flush=True)

concat=WORK/'clips.txt'
concat.write_text(''.join(f"file '{(WORK/f'part-{i+1:02}.mp4').as_posix()}'\n" for i in range(len(script))))
final=OUT/'fit-check-pitch.mp4'
run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','concat','-safe','0','-i',concat,
     '-c','copy','-movflags','+faststart','-metadata','title=Fit Check - On-device outfit roast and styling',
     '-metadata','comment=Narrated PDF walkthrough with synthetic narration; not a live app recording.',final])
(OUT/'fit-check-pitch.srt').write_text('\n'.join(captions))
(OUT/'fit-check-pitch-transcript.txt').write_text('\n\n'.join(item['text'] for item in script)+'\n')
(OUT/'verification.json').write_text(json.dumps({
    'source_pdf_sha256':hashlib.sha256(pdf.read_bytes()).hexdigest(),
    'duration_seconds':duration(final),'resolution':'1920x1080','fps':24,
    'narration':'macOS Samantha, synthetic; not the recorded Android app voice',
    'scope':'PDF pitch walkthrough; no live device interaction or inference wait is depicted',
    'chapters':manifest,
},indent=2)+'\n')
print(final)
