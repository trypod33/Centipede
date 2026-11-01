from PIL import Image
import colorsys, math, os

D = "app/src/main/res/drawable-nodpi/"

def hls(px):
    r,g,b,a = px
    h,l,s = colorsys.rgb_to_hls(r/255,g/255,b/255)
    return h*360, l, s

def ramp(px, stops):
    """stops: list of (lightness_threshold, rgb) applied by lightness, high first"""
    r,g,b,a = px
    h,l,s = hls(px)
    if a==0 or s<0.22: return px          # whites/stems/near-black untouched
    for thr,c in stops:
        if l > thr: return (c[0],c[1],c[2],a)
    return (stops[-1][1][0],stops[-1][1][1],stops[-1][1][2],a)

PUMPKIN = [(0.60,(255,186,84)),(0.42,(255,138,22)),(0.26,(214,84,6)),(0,(150,52,2))]
CANDY   = [(0.62,(255,244,200)),(0.46,(255,168,36)),(0.30,(255,118,18)),(0,(190,78,0))]
VIOLET  = [(0.55,(168,88,255)),(0.35,(118,48,200)),(0,(80,28,150))]
FLEECE  = [(0.62,(255,248,225)),(0,(255,236,190))]

def carve_face(im):
    w,h=im.size; px=im.load()
    xs,ys=[],[]
    for y in range(int(h*0.72)):
        for x in range(w):
            r,g,b,a=px[x,y]
            if a>0:
                mx,mn=max(r,g,b),min(r,g,b)
                if mx>0 and (mx-mn)/mx>0.30: xs.append(x); ys.append(y)
    if not xs: return im
    x0,x1,y0,y1=min(xs),max(xs),min(ys),max(ys)
    cw,ch=x1-x0,y1-y0
    glow=(255,224,72,255); edge=(96,34,0,255)
    eyeY=y0+int(ch*0.28); eyeH=max(4,int(ch*0.26)); eyeW=max(4,int(cw*0.16))
    exs=[x0+int(cw*0.27), x0+int(cw*0.73)]
    def put(x,y,c):
        if 0<=x<w and 0<=y<h: px[x,y]=c
    for cx in exs:
        for i in range(eyeH):
            wid=max(1, eyeW-int(eyeW*i/eyeH))
            for x in range(cx-wid//2, cx+wid//2+1): put(x,eyeY+i,glow)
            put(cx-wid//2-1,eyeY+i,edge); put(cx+wid//2+1,eyeY+i,edge)
        put(cx,eyeY-1,edge)
    mouthW=int(cw*0.58); mX0=x0+(cw-mouthW)//2
    mouthY=y0+int(ch*0.56); mouthH=max(4,int(ch*0.17))
    top=None
    for x in range(mX0,mX0+mouthW):
        t=(x-mX0)/max(1,mouthW-1)
        top=mouthY+int(round(2.2*math.sin(t*math.pi*2)*(mouthH*0.32)))
        for y in range(top,top+mouthH): put(x,y,glow)
        put(x,top-1,edge); put(x,top+mouthH,edge)
    if top is not None:
        for frac in (0.25,0.68):
            tx=mX0+int(mouthW*frac)
            for y in range(top,top+max(3,mouthH//2)): put(tx,y,(255,138,18,255))
    return im

files=sorted(f for f in os.listdir(D) if f.endswith(".png"))
for name in files:
    im=Image.open(D+name).convert("RGBA")
    px=im.load(); w,h=im.size
    n=name.lower()
    if "mushroom" in n and "mushp" not in n:
        # cap bbox from saturated pixels in the top 72%
        xs,ys=[],[]
        for y in range(int(h*0.72)):
            for x in range(w):
                r,g,b,a=px[x,y]
                if a>0:
                    mx,mn=max(r,g,b),min(r,g,b)
                    if mx>0 and (mx-mn)/mx>0.30: xs.append(x); ys.append(y)
        capBottom = max(ys) if ys else int(h*0.6)
        for y in range(h):
            for x in range(w):
                r,g,b,a=px[x,y]
                if a==0: continue
                if y>capBottom: continue          # stem keeps its own color
                hh,ll,ss=hls((r,g,b,a))
                if ss<0.22: continue              # pale highlight keeps
                if (hh<=22 or hh>=340) and ss>0.45 and max(r,g,b)>110:
                    px[x,y]=(96,30,8,a)           # red rim spots -> carved brown
                else:
                    px[x,y]=ramp(px[x,y],PUMPKIN)
        im=carve_face(im)
    elif "mushp" in n:
        for y in range(h):
            for x in range(w):
                px[x,y]=ramp(px[x,y],VIOLET)
    elif "c_body" in n or "c_head" in n:
        was_red={}
        for y in range(h):
            for x in range(w):
                r,g,b,a=px[x,y]
                if a==0: continue
                hh,ll,ss=hls((r,g,b,a))
                if (hh<=20 or hh>=342) and ss>0.4:
                    was_red[(x,y)]=True
                px[x,y]=ramp(px[x,y],CANDY)
        for (x,y) in was_red:                  # head/eye blocks stay arcade red
            r,g,b,a=px[x,y]
            px[x,y]=(235,44,28,a)
    elif "spider" in n:
        for y in range(h):
            for x in range(w):
                px[x,y]=ramp(px[x,y],VIOLET)
    elif "flea" in n:
        for y in range(h):
            for x in range(w):
                r,g,b,a=px[x,y]
                if a==0: continue
                hh,ll,ss=hls((r,g,b,a))
                if ss<0.22: continue
                if 190<=hh<=265: px[x,y]=(255,250,238,a)   # blue cap -> candy white
                else: px[x,y]=(255,142,22,a)               # body -> candy orange
    elif "ship" in n:
        for y in range(h):
            for x in range(w):
                r,g,b,a=px[x,y]
                if a==0: continue
                hh,ll,ss=hls((r,g,b,a))
                if ss>=0.22 and 165<=hh<=215:
                    px[x,y]=(196,92,255,a)                 # cyan bands -> violet
    im.save(D+name)
print("done", len(files))
