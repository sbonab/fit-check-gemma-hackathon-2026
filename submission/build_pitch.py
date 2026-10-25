"""Rebuild the two-page submission PDF: python3 submission/build_pitch.py."""
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
c.save()
print(OUT)
