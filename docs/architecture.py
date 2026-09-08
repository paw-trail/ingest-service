W, H = 2450, 1540
FONT = "Noto Sans CJK KR, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif"
C = {"ext":("#F3F4F6","#9CA3AF"),"edge":("#DBEAFE","#3B82F6"),"plat":("#E0E7FF","#6366F1"),
     "dom":("#DCFCE7","#22C55E"),"domn":("#F0FDF4","#86EFAC"),"data":("#FFEDD5","#F97316"),
     "obs":("#F3E8FF","#A855F7"),"fut":("#FFFFFF","#9CA3AF"),"kafka":("#FFF7ED","#F97316")}
out=[]
def rect(x,y,w,h,kind,r=12,dash=False,sw=2.2):
    f,s=C[kind]; d=' stroke-dasharray="9,7"' if dash else ''
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{f}" stroke="{s}" stroke-width="{sw}"{d}/>')
from PIL import ImageFont
_font = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 40)
def _tw(s, size): return _font.getlength(s) * size / 40
def text(x,y,s,size=15,w="normal",anchor="middle",fill="#111827",halo=False):
    if halo:
        tw=_tw(s,size)+10; x0 = x-tw/2 if anchor=="middle" else (x-4 if anchor=="start" else x-tw+4)
        out.append(f'<rect x="{x0:.0f}" y="{y-size+1:.0f}" width="{tw:.0f}" height="{size+6}" rx="4" fill="#FFFFFF" opacity="0.92"/>')
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{w}" text-anchor="{anchor}" fill="{fill}">{s}</text>')
def box(cx,cy,w,h,kind,title,sub=None,dash=False,tsize=18):
    rect(cx-w/2,cy-h/2,w,h,kind,dash=dash)
    if sub: text(cx,cy-4,title,tsize,"bold"); text(cx,cy+18,sub,12,fill="#4B5563")
    else:   text(cx,cy+6,title,tsize,"bold")
def poly(pts,color,label=None,dash=False,lx=None,ly=None,w=2.4,anchor="middle",both=False,lsize=13):
    d=' stroke-dasharray="7,6"' if dash else ''
    ms=f' marker-start="url(#as-{color[1:]})"' if both else ''
    out.append(f'<polyline points="{" ".join(f"{x},{y}" for x,y in pts)}" fill="none" stroke="{color}" stroke-width="{w}" marker-end="url(#ah-{color[1:]})"{ms}{d}/>')
    if label: text(lx,ly,label,lsize,"normal",anchor,color,halo=True)
def region(x,y,w,h,label,color,dash="7,7",lsize=15,lpos="top"):
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="18" fill="none" stroke="{color}" stroke-width="2" stroke-dasharray="{dash}" opacity="0.85"/>')
    text(x+16,y+24 if lpos=="top" else y+h-12,label,lsize,"bold","start",color)
colors=["#374151","#3B82F6","#6366F1","#16A34A","#F97316","#A855F7","#9CA3AF","#0EA5E9"]
defs="".join(f'<marker id="ah-{c[1:]}" markerWidth="11" markerHeight="9" refX="10" refY="4.5" orient="auto"><path d="M0,0 L11,4.5 L0,9 z" fill="{c}"/></marker><marker id="as-{c[1:]}" markerWidth="11" markerHeight="9" refX="1" refY="4.5" orient="auto"><path d="M11,0 L0,4.5 L11,9 z" fill="{c}"/></marker>' for c in colors)

# ── 경계
region(30,160,W-60,H-210,"AWS VPC  ─  배포할 때의 모습.  로컬에서는 이 전체가 docker 네트워크 pawtrail 하나","#0EA5E9",dash="12,8",lsize=16)
region(60,190,300,120,"퍼블릭 서브넷","#0EA5E9",dash="5,5")
region(60,335,W-120,H-405,"프라이빗 서브넷  ─  바깥에서 직접 닿을 수 없음.  EC2 전부 여기","#0EA5E9",dash="5,5")

# ── VPC 밖
box(200,75,260,72,"ext","브라우저","React 프론트 · 쿠키에 JWT")
box(560,75,300,72,"ext","Google OAuth · Gmail SMTP","auth 만 부름")
box(900,75,300,72,"ext","공공데이터 API · 집중률 API · LLM","ingest · congestion · extract 가 부름")
box(1210,75,260,72,"ext","GitHub","paw-trail/config 저장소")

# ── 왼쪽 입구 열
box(200,250,240,64,"fut","ALB","예정 · HTTPS 종단",dash=True)
box(200,470,240,70,"fut","nginx  (edge)","예정 · 정적 파일 + /api 프록시",dash=True)
box(200,700,260,80,"edge","gateway-server  :8080","JWT 검증 · 라우팅 · 헤더 주입")
# ── 위 플랫폼
box(1210,230,280,70,"plat","config-server  :8888","설정 저장소를 읽어 내려 줌")
box(2050,230,280,70,"plat","eureka-server  :8761","이름 → 주소 장부")

# ── 가운데 도메인 격자 (부르는 쪽 → 받는 쪽 = 왼쪽 → 오른쪽)
region(600,340,1230,770,"도메인 서비스 14개  ─  왼쪽이 부르고 오른쪽이 받음.  진한 초록 = 자기 DB 있음 · 연한 초록 = 무상태","#22C55E",dash="5,5",lpos="bottom")
COL={0:760,1:1210,2:1660}; ROW={r:430+r*150 for r in range(5)}; BW,BH=220,64
dom={ # name:(col,row,port,db,pub,sub,extra)
 "review":(0,0,8094,True,0,1,""),"notification":(0,1,8093,True,0,3,""),"search":(0,2,8087,True,0,2,""),"extract":(0,3,8089,False,0,0,"→ LLM"),"auth":(0,4,8081,True,2,0,"→ Google · Gmail"),
 "user":(1,0,8082,True,0,2,""),"ingest":(1,1,8088,True,0,0,"→ 공공 API"),"verdict":(1,2,8086,False,0,2,""),"report":(1,4,8092,True,1,1,""),
 "place":(2,0,8084,True,1,0,""),"pet":(2,1,8083,True,1,1,""),"policy":(2,2,8085,True,1,0,""),"congestion":(2,3,8090,False,0,0,"→ 집중률 API"),"route":(2,4,8091,False,0,0,"")}
P={}
for n,(c,r,port,db,pub,sub,ex) in dom.items():
    cx,cy=COL[c],ROW[r]; P[n]=(cx,cy)
    rect(cx-BW/2,cy-BH/2,BW,BH,"dom" if db else "domn",r=10)
    text(cx,cy-6,n,17,"bold")
    tags=f":{port} · "+("DB" if db else "무상태")+(f" · ↑{pub}" if pub else "")+(f" · ↓{sub}" if sub else "")+(f" · {ex}" if ex else "")
    text(cx,cy+16,tags,11,fill="#4B5563")
def L(n): return (P[n][0]-BW/2, P[n][1])
def R(n): return (P[n][0]+BW/2, P[n][1])
def T(n): return (P[n][0], P[n][1]-BH/2)
def B(n): return (P[n][0], P[n][1]+BH/2)
G="#16A34A"; y=lambda n,d=0: P[n][1]+d
# 같은 행 직선
poly([R("review"),L("user")],G,"/internal/users",lx=985,ly=y("review")-10)
poly([R("search"),L("verdict")],G,"/internal/verdicts/batch",lx=985,ly=y("search")-10)
poly([R("user"),L("place")],G,"일정 조립",lx=1435,ly=y("user")-10)
poly([R("verdict"),L("policy")],G,"/internal/policies",lx=1435,ly=y("verdict")-10)
# 꺾은 선 — 열 사이 레인 x 를 하나씩 배정
poly([(870,y("notification")),(940,y("notification")),(940,y("user",16)),(1100,y("user",16))],G,"/internal/favorites",lx=1020,ly=y("user",34))
poly([(1320,y("verdict",16)),(1390,y("verdict",16)),(1390,y("pet",16)),(1550,y("pet",16))],G,"/internal/pets",lx=1470,ly=y("pet",10))
poly([(1210,y("ingest",-32)),(1210,505),(1460,505),(1460,y("place",16)),(1550,y("place",16))],G,"bulk  ↔  documents",lx=1350,ly=499,both=True)
poly([(870,y("extract")),(980,y("extract")),(980,y("ingest",16)),(1100,y("ingest",16))],G,"/internal/raw",lx=1040,ly=y("ingest",10))
poly([(870,y("extract",16)),(1020,y("extract",16)),(1020,805),(1470,805),(1470,y("policy",16)),(1550,y("policy",16))],G,"/internal/policies/bulk",lx=1245,ly=799)
poly([(870,y("search",-16)),(1060,y("search",-16)),(1060,380),(1600,380),(1600,398)],G,"/internal/places  (재색인)",lx=1330,ly=374)
poly([(870,y("search",16)),(1040,y("search",16)),(1040,820),(1500,820),(1500,y("congestion")),(1550,y("congestion"))],G,"/internal/congestion/batch",lx=1270,ly=836)
poly([(870,y("review",16)),(1000,y("review",16)),(1000,520),(1400,520),(1400,y("pet",-16)),(1550,y("pet",-16))],G,"/internal/pets  (스냅샷)",lx=1150,ly=514)
poly([(870,y("notification",-16)),(910,y("notification",-16)),(910,360),(1620,360),(1620,398)],G,"/internal/places",lx=1330,ly=354)

# ── 게이트웨이 · 플랫폼 선
BL="#3B82F6"; V="#6366F1"
poly([(200,111),(200,218)],BL,"①",lx=228,ly=150,lsize=15)
poly([(200,282),(200,435)],BL,dash=True)
poly([(200,505),(200,660)],BL)
poly([(330,700),(600,700)],BL,"③ 라우팅  +  X-User-Id · X-User-Role 헤더",lx=340,ly=688,anchor="start",w=3.5)
poly([(330,672),(420,672),(420,300),(2110,300),(2110,265)],V,"② 유레카에 주소 조회  \"place 어디 있어?\"",lx=1500,ly=294)
poly([(1210,111),(1210,195)],V,"읽음",lx=1240,ly=158)
poly([(1210,265),(1210,340)],V,"기동 시 설정",lx=1280,ly=325)
poly([(1830,400),(1990,400),(1990,265)],V,"등록 · 하트비트",lx=2060,ly=395)

# ── 오른쪽 데이터
box(2050,620,300,80,"data","PostgreSQL  :5432","DB 10개 · 서비스마다 자기 것만")
box(2050,780,300,80,"data","Redis  :6379","auth · verdict · search 만")
poly([(1830,620),(1900,620)],"#F97316","JPA · Flyway",lx=1865,ly=612)
poly([(1830,780),(1900,780)],"#F97316")

# ── 아래 Kafka
rect(600,1190,1230,150,"kafka",r=14)
text(620,1216,"Kafka  :9092 / :29092   ─   토픽 6개 + .dlq 6개.   발행하는 쪽  →  소비하는 쪽",16,"bold","start","#C2410C")
topics=[("account.created","auth","user"),("account.withdrawn","auth","user · pet · report · review · notification"),
        ("pet.profile.updated","pet","verdict"),("place.updated","place","search"),
        ("policy.changed","policy","search · verdict · notification"),("report.reviewed","report","notification")]
for i,(t,p,s) in enumerate(topics):
    c=i%2; r=i//2; x=620+c*610; yy=1250+r*28
    text(x,yy,t,14,"bold","start","#7C2D12"); text(x+215,yy,f"{p}  →  {s}",13,"normal","start","#4B5563")
poly([(1215,1110),(1215,1190)],"#F97316","Outbox 로 발행  ·  Inbox 로 소비",lx=1330,ly=1155,both=True,anchor="start")

# ── 관측
region(1900,1190,420,150,"관측  ─  observability 프로파일","#A855F7",dash="5,5")
box(2010,1240,180,44,"obs","Prometheus",tsize=13); box(2210,1240,180,44,"obs","Loki",tsize=13)
box(2010,1296,180,44,"obs","Zipkin",tsize=13); box(2210,1296,180,44,"obs","Grafana",tsize=13)
poly([(1830,1080),(1900,1200)],"#A855F7",dash=True); text(1800,1150,"로그 · 지표 · 추적",13,fill="#A855F7",halo=True)

# ── 범례
text(600,1400,"초록 실선 = 동기 호출 (/internal).  부르는 쪽 → 받는 쪽.  화살표 양끝 = 양방향",14,"normal","start","#4B5563")
text(600,1426,"Kafka 표 = 이벤트.  ↑n = 발행하는 토픽 수 · ↓n = 소비하는 토픽 수.  점선 = 예정 또는 바깥",14,"normal","start","#4B5563")
text(600,1452,"지금 컨테이너로 도는 것은 auth 뿐. 나머지는 각자 IntelliJ 로 띄움.  포트는 config 저장소가 단일 출처",14,"normal","start","#166534")

svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<defs>{defs}</defs><rect width="{W}" height="{H}" fill="#FFFFFF"/>
{chr(10).join(out)}
<text x="{W-30}" y="{H-14}" font-size="12" text-anchor="end" fill="#9CA3AF">함께하개 (paw-trail) 전체 구조 · 2026.09</text>
</svg>'''
open("architecture.svg","w",encoding="utf-8").write(svg)
import cairosvg; cairosvg.svg2png(url="architecture.svg",write_to="architecture.png",output_width=3200); print("ok")
