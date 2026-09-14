# 레포 중심 그림 — 가운데에 그 레포, 주변에 직접 연결된 것만
#
# 이 파일은 docs/focus-ingest-service.svg 와 .png 를 만듭니다.
# 다른 레포에도 같은 이름의 파일이 있고 앞부분(D 클래스)은 같습니다.
# 아래 블록만 이 레포의 것입니다.
#
# 돌리는 법
#   pip install pillow cairosvg
#   cd docs && python focus.py
#   글꼴은 Noto Sans CJK 를 씁니다. 없으면 _f 경로를 고치십시오.
from PIL import ImageFont
import cairosvg
FONT="Noto Sans CJK KR, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif"
_f=ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",40)
def tw(s,size): return _f.getlength(s)*size/40
C={"ext":("#F3F4F6","#9CA3AF"),"edge":("#DBEAFE","#3B82F6"),"plat":("#E0E7FF","#6366F1"),"dom":("#DCFCE7","#22C55E"),
   "domn":("#F0FDF4","#86EFAC"),"data":("#FFEDD5","#F97316"),"obs":("#F3E8FF","#A855F7"),"fut":("#FFFFFF","#9CA3AF"),
   "lib":("#FEF3C7","#D97706"),"me":("#FFFFFF","#111827")}
colors=["#374151","#3B82F6","#6366F1","#16A34A","#F97316","#A855F7","#9CA3AF","#D97706","#111827"]
DEFS="".join(f'<marker id="ah-{c[1:]}" markerWidth="11" markerHeight="9" refX="10" refY="4.5" orient="auto"><path d="M0,0 L11,4.5 L0,9 z" fill="{c}"/></marker><marker id="as-{c[1:]}" markerWidth="11" markerHeight="9" refX="1" refY="4.5" orient="auto"><path d="M11,0 L0,4.5 L11,9 z" fill="{c}"/></marker>' for c in colors)

class D:
    def __init__(s,W,H): s.W,s.H,s.out,s.N=W,H,[],{}
    def text(s,x,y,t,size=14,w="normal",anchor="middle",fill="#111827",bg=False):
        t=t.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        if bg:
            ww=tw(t,size)+10; x0=x-ww/2 if anchor=="middle" else (x-4 if anchor=="start" else x-ww+4)
            s.out.append(f'<rect x="{x0:.0f}" y="{y-size+1:.0f}" width="{ww:.0f}" height="{size+6}" rx="4" fill="#FFFFFF" opacity="0.92"/>')
        s.out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{w}" text-anchor="{anchor}" fill="{fill}">{t}</text>')
    def node(s,name,cx,cy,w,h,kind,title,sub=None,dash=False,tsize=17,sw=2.2):
        f,st=C[kind]; d=' stroke-dasharray="9,7"' if dash else ''
        s.out.append(f'<rect x="{cx-w/2}" y="{cy-h/2}" width="{w}" height="{h}" rx="12" fill="{f}" stroke="{st}" stroke-width="{sw}"{d}/>')
        lines=sub.split("|") if sub else []
        y0=cy-6-8*len(lines)+ (0 if lines else 12)
        s.text(cx,y0,title,tsize,"bold")
        for i,l in enumerate(lines): s.text(cx,y0+20+i*16,l,12,fill="#4B5563")
        s.N[name]=(cx,cy,w,h)
    def me(s,name,cx,cy,w,h,kind,title,sub=None):
        f,st=C[kind]
        s.out.append(f'<rect x="{cx-w/2-8}" y="{cy-h/2-8}" width="{w+16}" height="{h+16}" rx="18" fill="none" stroke="{st}" stroke-width="3" opacity="0.5"/>')
        s.node(name,cx,cy,w,h,kind,title,sub,tsize=22,sw=3.5)
        s.text(cx-w/2-8,cy-h/2-14,"이 레포",13,"bold","start",st)
    def side(s,name,which):
        cx,cy,w,h=s.N[name]
        return {"l":(cx-w/2,cy),"r":(cx+w/2,cy),"t":(cx,cy-h/2),"b":(cx,cy+h/2)}[which]
    def edge(s,a,sa,b,sb,color,label=None,dash=False,both=False,w=2.4,via=None,lx=None,ly=None,anchor="middle",lsize=13,a_pt=None,b_pt=None):
        p1=a_pt or s.side(a,sa); p2=b_pt or s.side(b,sb); pts=[p1]+(via or [])+[p2]
        d=' stroke-dasharray="7,6"' if dash else ''; ms=f' marker-start="url(#as-{color[1:]})"' if both else ''
        s.out.append(f'<polyline points="{" ".join(f"{x},{y}" for x,y in pts)}" fill="none" stroke="{color}" stroke-width="{w}" marker-end="url(#ah-{color[1:]})"{ms}{d}/>')
        if label:
            if lx is None:
                mid=pts[len(pts)//2] if len(pts)>2 else ((p1[0]+p2[0])/2,(p1[1]+p2[1])/2); lx,ly=mid[0],mid[1]-8
            s.text(lx,ly,label,lsize,fill=color,anchor=anchor,bg=True)
    def note(s,x,y,t,color="#4B5563",size=13,anchor="start"): s.text(x,y,t,size,fill=color,anchor=anchor)
    def save(s,name,foot):
        svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{s.W}" height="{s.H}" viewBox="0 0 {s.W} {s.H}" font-family="{FONT}">
<defs>{DEFS}</defs><rect width="{s.W}" height="{s.H}" fill="#FFFFFF"/>
{chr(10).join(s.out)}
<text x="{s.W-24}" y="{s.H-12}" font-size="11" text-anchor="end" fill="#9CA3AF">{foot}</text></svg>'''
        open(f"focus-{name}.svg","w",encoding="utf-8").write(svg)
        cairosvg.svg2png(url=f"focus-{name}.svg",write_to=f"focus-{name}.png",output_width=2000)
        print("ok",name)

B,V,G,O,P,X,L="#3B82F6","#6366F1","#16A34A","#F97316","#A855F7","#9CA3AF","#D97706"


# ── ingest-service
d=D(1980,1010)
d.me("ig",840,470,380,110,"dom","ingest-service  :8088",
     "공공데이터를 받아 담고 place 로 넘김|API 5개 · 화면 없음 · 상시 미기동")

d.node("jk",180,140,260,90,"fut","Jenkins 잡","수집을 언제 부를지 정함|아직 없음",dash=True)
d.node("api",180,360,260,100,"ext","공공데이터포털","반려동물 동반여행 · 고캠핑|오퍼레이션마다 하루 1,000회",dash=True)
d.node("csv",180,590,260,100,"ext","CSV 파일 둘","문화정보원 · 행정안전부|이미지 안에 담겨 있음")
d.node("gw",180,830,260,70,"edge","gateway-server","여기로는 라우팅하지 않음",dash=True)

d.node("cf",840,120,320,80,"plat","config-server","포트 · DB · 인증키 · 소스별 값")
d.node("eu",1640,110,300,80,"plat","eureka-server","등록")
d.node("pg",1640,310,300,110,"data","PostgreSQL  raw_db",
       "raw_document 17,471건 · place_id 17,463|ingest_run  (표 2개)|소스 넷 가운데 셋만 담김")
d.node("ex",1640,570,300,100,"domn","extract-service","대기 문서를 가져가 해석|아직 없음")
d.node("pl",1640,830,300,110,"domn","place-service","소스가 겹친 것을 한 장소로|넘긴 결과를 돌려줌|동물병원은 바로 받음")

d.edge("jk","r","ig","l",X,"POST /internal/ingest/trigger",dash=True,
       via=[(520,140),(520,440)],b_pt=(650,440),lx=340,ly=132,anchor="start")
d.edge("ig","l","api","r",O,"목록 · 상세를 부름",dash=True,
       a_pt=(650,470),via=[(520,470),(520,360)],lx=340,ly=345,anchor="start")
d.edge("csv","r","ig","l",O,"파일을 읽음",
       via=[(520,590),(520,500)],b_pt=(650,500),lx=530,ly=560,anchor="start")
d.edge("gw","r","ig","b",X,"라우팅하지 않음",dash=True,
       via=[(600,830),(600,560)],b_pt=(760,525),lx=610,ly=730,anchor="start")

d.edge("cf","b","ig","t",V,"기동 시 설정")
d.edge("ig","r","eu","l",V,"등록",via=[(1300,470),(1300,110)],lx=1310,ly=260,anchor="start")
d.edge("ig","r","pg","l",O,"JPA · Flyway V20 · V21",
       via=[(1300,470),(1300,310)],lx=1310,ly=385,anchor="start")
d.edge("ex","l","ig","r",G,"GET /internal/raw · PATCH 로 결과 반영",
       a_pt=(1490,570),via=[(1180,570),(1180,490)],b_pt=(1030,490),lx=1480,ly=562,anchor="end")

# place 로 가는 길이 둘 — 담아 둔 것을 넘기는 것과 바로 보내는 것
d.edge("ig","r","pl","l",G,"LINK  소스 셋을 담아 둔 뒤 넘김",
       a_pt=(1030,512),via=[(1390,512),(1390,800)],b_pt=(1490,800),lx=1400,ly=690,anchor="start")
d.edge("pl","l","ig","r",G,"GET /internal/raw/{placeId}/documents",
       a_pt=(1490,858),via=[(1250,858),(1250,532)],b_pt=(1030,532),lx=1050,ly=850,anchor="start")
d.edge("ig","b","pl","b",G,"DIRECT  raw 를 거치지 않고 바로",
       a_pt=(900,525),via=[(900,940),(1560,940)],b_pt=(1560,885),lx=910,ly=930,anchor="start")

d.note(40,968,"소스가 넷인데 셋만 raw_db 에 담김 — 행정안전부 동물병원은 조건 문장이 없어 해석할 것도 원문으로 보여줄 것도 없어, 읽는 자리에서 바로 place 로 보냄")
d.note(40,990,"관광공사 상세는 한 번에 3,237회라 하루 한도를 넘김 — 그날 받은 데까지 기록하고 다음 날 그 자리부터 이어받음 · 게이트웨이가 /internal 을 라우팅하지 않고 Kafka 도 쓰지 않음")
d.save("ingest-service","ingest-service 를 중심으로 · 직접 연결된 것만")
