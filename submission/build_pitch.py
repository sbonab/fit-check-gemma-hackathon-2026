"""Rebuild the five-page submission PDF: python3 submission/build_pitch.py."""
from pathlib import Path
import json
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import ImageReader
from reportlab.platypus import Paragraph
from reportlab.lib.styles import ParagraphStyle

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/pdf/fit-check-pitch.pdf'
FONT = Path('/System/Library/Fonts/Supplemental')
# On other platforms, use available Arial-compatible system fonts.
for name, filename in [('Sans','Arial.ttf'),('Bold','Arial Bold.ttf'),('Display','Georgia.ttf')]:
    if (FONT / filename).exists():
        pdfmetrics.registerFont(TTFont(name, str(FONT / filename)))
    else:
        fallback = {'Sans':'Helvetica','Bold':'Helvetica-Bold','Display':'Times-Roman'}[name]
        pdfmetrics.registerFont(pdfmetrics.Font(name, fallback, 'WinAnsiEncoding'))

W,H = 960,540
BG,INK,MUTED,LIME = map(HexColor, ['#F6F3EA','#20231E','#64685F','#DBEF8F'])
c = canvas.Canvas(str(OUT), pagesize=(W,H))
c.setTitle('Fit Check - Mobile AI draft pitch')
c.setAuthor('Fit Check')

def text(x,y,t,size=14,font='Sans',color=INK):
    c.setFillColor(color); c.setFont(font,size); c.drawString(x,y,t)

def para(x,top,t,width,size=16,leading=None,font='Sans',color=INK):
    p=Paragraph(t,ParagraphStyle('body',fontName=font,fontSize=size,
        leading=leading or size*1.35,textColor=color))
    _,height=p.wrap(width,1000); p.drawOn(c,x,top-height); return top-height

def base(n):
    c.setFillColor(BG);c.rect(0,0,W,H,fill=1,stroke=0)
    text(46,505,'FIT CHECK',12,'Bold')
    text(580,505,'GEMMA HARDWARE HACKATHON  /  MOBILE AI',10,'Bold',MUTED)
    c.setStrokeColor(HexColor('#D7D8CC'));c.line(46,38,914,38)
    text(46,21,'Draft prototype  |  October 24, 2026',9,color=MUTED)
    text(890,21,f'{n:02}',9,color=MUTED)

OUT.parent.mkdir(parents=True,exist_ok=True)
base(1)
text(46,420,'Your outfit.',49,'Display')
text(46,363,'A roast.',49,'Display')
text(46,306,'A better idea.',49,'Display')
para(48,270,'Point the camera. Ask for a roast.<br/>Then ask how to make the outfit better.',510,21,29)
c.setFillColor(LIME);c.roundRect(46,115,470,49,14,fill=1,stroke=0)
text(64,133,'"Hey Gemma, roast my outfit."',20,'Bold')
text(48,78,'Gemma 4 E2B + LiteRT-LM. Everything on the phone.',13,'Bold')
shot=ImageReader(str(ROOT/'submission/assets/app-screenshot.png'))
sw,sh=shot.getSize(); ih=422;iw=ih*sw/sh
c.setFillColor(INK);c.roundRect(649,54,iw+12,ih+12,24,fill=1,stroke=0)
c.drawImage(shot,655,60,width=iw,height=ih,mask='auto')
text(635,43,'Real app screenshot - earlier development build',8,color=MUTED)
c.showPage()

base(2)
text(46,450,'The mirror talks back.',38,'Display')
text(46,416,'ACTUAL GEMMA OUTPUT  /  SAVED OUTFIT REPLAY',10,'Bold',MUTED)
sentences=json.loads((ROOT/'submission/assets/sample-response.json').read_text())['sentences']
y=392
for s in sentences:
    y=para(46,y,s,520,17,23)-15
text(46,119,'Then: "How would you make it better?"',15,'Bold')
para(46,97,'Styling advice uses the same original outfit photo.',520,13,color=MUTED)
c.setFillColor(HexColor('#E9EBDC'));c.roundRect(620,169,294,278,17,fill=1,stroke=0)
text(640,419,'WHY BEST USE OF LITERT',11,'Bold')
y=390
for title,body in [
    ('A full multimodal experience','Gemma vision, audio and language on Pixel.'),
    ('Deliberate runtime engineering','GPU generation; constrained JSON output.'),
    ('Measured on the actual phone','Prepared contexts + 140 visual tokens.'),
    ('Local by construction','No Internet permission; offline speech.'),
]:
    text(640,y,title,14,'Bold');y=para(640,y-9,body,249,12,16)-17
para(625,146,'TWO SAVED-PHOTO INFERENCE RUNS',280,10,font='Bold',color=MUTED)
para(625,126,'12.6 / 21.8 s baseline → 7.3 / 13.8 s tuned.<br/>Small sample; excludes capture and speech.<br/>Live voice and clothing accuracy need tuning.',280,11,16)
para(46,65,'Selected, unedited model output. Screenshot and response are separate captures of the same outfit.',850,9,color=MUTED)
c.showPage()

# Track slides follow the original two pages. All diagrams describe the current
# implementation; wake reliability and NPU work are explicitly bounded below.
PANEL=HexColor('#E9EBDC')
SOFT=HexColor('#E2E1D8')

def panel(x,y,w,h,fill=PANEL,r=16):
    c.setFillColor(fill);c.roundRect(x,y,w,h,r,fill=1,stroke=0)

def line(x1,y1,x2,y2,color=INK,width=1.3):
    c.setStrokeColor(color);c.setLineWidth(width);c.line(x1,y1,x2,y2)

def arrow(x1,y,x2,color=INK):
    line(x1,y,x2,y,color)
    line(x2-6,y+4,x2,y,color);line(x2-6,y-4,x2,y,color)

def track(label,title,subtitle):
    text(46,462,label,11,'Bold',MUTED)
    text(46,414,title,36,'Display')
    para(48,390,subtitle,850,16,22)

base(3)
track('TRACK 01  /  MOBILE AI', 'One outfit. Two conversations.',
      'A camera-first Gemma app that turns an outfit into spoken comedy, then useful advice.')
for x,num,title,body in [
    (46,'01','Frame the outfit','Use the front or rear camera.<br/>Talk or tap to start.'),
    (344,'02','Hear the roast','An overall verdict, then jokes<br/>about visible clothing.'),
    (642,'03','Ask for a better fit','Styling advice reuses the<br/>original outfit photo.'),
]:
    panel(x,202,272,146,LIME if num=='02' else PANEL)
    text(x+18,322,num,12,'Bold',MUTED)
    text(x+18,288,title,20,'Bold')
    para(x+18,267,body,237,14,20)
arrow(321,272,339);arrow(619,272,637)
line(480,200,480,178,MUTED)
line(183,178,778,178,MUTED)
line(183,178,183,199,MUTED);line(778,178,778,199,MUTED)
text(316,160,'One original photo stays in the local session',12,'Bold',MUTED)
text(46,127,'WHY IT MERITS CONSIDERATION',10,'Bold',MUTED)
para(46,109,'Gemma connects vision, speech input and language in a playful mobile flow. '
     'The next question has context, so the experience continues beyond a single caption.',510,15,21)
para(621,127,'ON-DEVICE EVIDENCE',270,10,font='Bold',color=MUTED)
para(621,109,'Camera → roast → speech → advice verified.<br/>Buttons remain the reliable demo trigger;<br/>live voice and clothing accuracy need tuning.',290,12,17)
c.showPage()

base(4)
track('SPECIAL AWARD  /  BEST USE OF LITERT', 'The runtime is part of the product.',
      'LiteRT-LM 0.18.1 runs Gemma vision, audio transcription and language generation on the Pixel.')
panel(46,260,175,87)
text(62,321,'PHOTO + AUDIO',11,'Bold')
para(62,304,'CameraX / AudioRecord<br/>Foreground capture',144,12,17)
arrow(227,303,258)
panel(266,246,374,112,LIME)
text(284,332,'GEMMA 4 E2B  /  LITERT-LM',15,'Bold')
para(284,314,'GPU language generation · CPU image/audio encoders<br/>'
     '140 visual tokens · prepared system contexts<br/>JSON-schema constrained response decoding',337,12,19)
arrow(647,303,679)
panel(687,260,227,87)
text(703,321,'VALIDATE + SPEAK',11,'Bold')
para(703,304,'Three lines / styling advice<br/>Offline Android TTS',192,12,17)
text(46,220,'PROFILED ON THE ACTUAL PHONE',10,'Bold',MUTED)
# Vector bar chart: repeated runs of the same saved photo, not an average or SLA.
chartX,scale=130,14.0
for row,label,before,after in [(0,'Run 1',12.63,7.29),(1,'Run 2',21.75,13.85)]:
    y=184-row*55
    text(46,y-5,label,12,'Bold')
    panel(chartX,y,scale*before,12,INK,3)
    text(chartX+scale*before+8,y+1,f'{before:.1f} s',10)
    panel(chartX,y-19,scale*after,12,LIME,3)
    text(chartX+scale*after+8,y-18,f'{after:.1f} s',10)
panel(130,84,10,10,INK,2);text(147,84,'Baseline',10,color=MUTED)
panel(215,84,10,10,LIME,2);text(232,84,'Selected settings',10,color=MUTED)
text(556,215,'WHY IT MERITS CONSIDERATION',10,'Bold',MUTED)
para(556,197,'We measured setup and generation, compared runtime choices, and kept the '
     'settings that worked on our device. Source and tradeoffs are public.',350,14,20)
para(556,123,'Prewarming moves work to startup/playback.<br/>'
     'The smaller image budget trades detail for speed.<br/>MTP was tested and left off after a runtime error.',350,11,16,color=MUTED)
para(46,65,'Two runs per configuration on one saved photo; output length and device state vary. '
     'Timings exclude model load, microphone transcription, camera capture and speech. No guaranteed speedup.',865,8.5,11,color=MUTED)
c.showPage()

base(5)
track('BEST IN SHOW  /  END-TO-END ANDROID & PIXEL AI EXPERIENCE',
      'A whole interaction, on one phone.',
      'Native camera, foreground audio, local reasoning and spoken follow-up work together.')
# A device boundary makes the deployment claim concrete without implying NPU use.
c.setStrokeColor(INK);c.setLineWidth(1.5);c.roundRect(46,131,522,217,23,fill=0,stroke=1)
text(65,321,'PIXEL DEVICE BOUNDARY',11,'Bold',MUTED)
nodes=[(67,229,'Camera','Front / rear'),(236,229,'Gemma','LiteRT-LM'),
       (405,229,'Voice out','Offline TTS')]
for x,y,title,body in nodes:
    panel(x,y,142,64,LIME if title=='Gemma' else PANEL,12)
    text(x+13,y+39,title,16,'Bold');text(x+13,y+17,body,11)
arrow(213,261,231);arrow(382,261,400)
panel(67,155,480,53,PANEL,12)
text(82,186,'Local app coordination',13,'Bold')
text(82,168,'Mic pauses for inference/playback; one original photo for follow-up.',11)
line(307,228,307,209,MUTED)
text(601,332,'WHY IT MERITS CONSIDERATION',10,'Bold',MUTED)
para(601,311,'Native device integration',314,18,font='Bold')
para(601,284,'Both cameras, microphone lifecycle, captions and offline speech in a focused Android app.',304,14,20)
para(601,224,'Private inference by design',314,18,font='Bold')
para(601,197,'No Internet permission, no cloud model and no server dependency. GPU inference is implemented; TPU acceleration is future work.',304,14,20)
panel(46,62,868,49,LIME,12)
text(63,92,'VERIFIED TODAY',10,'Bold')
text(63,75,'46 unit tests + build/lint; device camera, spoken output and same-photo follow-up checks.',12)
para(46,57,'Still to improve: live wake reliability, garment specificity and debug-media deletion controls.',865,8.5,11,color=MUTED)
c.save()
print(OUT)
