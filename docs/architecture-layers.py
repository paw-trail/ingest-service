# 세로 층 구조도 — 앞서 만든 판의 재현
W, H = 1600, 1110
FONT = "Noto Sans CJK KR, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif"
C = {"ext":("#F3F4F6","#9CA3AF"),"edge":("#DBEAFE","#3B82F6"),"plat":("#E0E7FF","#6366F1"),"dom":("#DCFCE7","#22C55E"),
     "domn":("#F0FDF4","#86EFAC"),"data":("#FFEDD5","#F97316"),"obs":("#F3E8FF","#A855F7"),"fut":("#FFFFFF","#9CA3AF")}
out=[]
def rect(x,y,w,h,kind,r=10,dash=False):
    f,s=C[kind]; d=' stroke-dasharray="8,6"' if dash else ''
    out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{f}" stroke="{s}" stroke-width="2"{d}/>')
def text(x,y,s,size=15,w="normal",anchor="middle",fill="#111827"):
    out.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{w}" text-anchor="{anchor}" fill="{fill}">{s}</text>')
def box(x,y,w,h,kind,title,sub=None,dash=False,tsize=16):
    rect(x,y,w,h,kind,dash=dash)
    if sub: text(x+w/2,y+h/2-4,title,tsize,"bold"); text(x+w/2,y+h/2+16,sub,12,fill="#4B5563")
    else:   text(x+w/2,y+h/2+6,title,tsize,"bold")
def arrow(x1,y1,x2,y2,color="#374151",label=None,dash=False,lx=None,ly=None,w=2,anchor="middle"):
    d=' stroke-dasharray="6,5"' if dash else ''
    out.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{w}" marker-end="url(#ah-{color[1:]})"{d}/>')
    if label: text(lx if lx is not None else (x1+x2)/2, ly if ly is not None else (y1+y2)/2-6, label, 12, fill=color, anchor=anchor)
def band(y,h,label,color):
    out.append(f'<rect x="20" y="{y}" width="{W-40}" height="{h}" rx="14" fill="none" stroke="{color}" stroke-width="1.5" stroke-dasharray="4,4" opacity="0.6"/>')
    text(34,y+20,label,13,"bold","start",color)
colors=["#374151","#3B82F6","#6366F1","#22C55E","#F97316","#A855F7","#9CA3AF"]
defs="".join(f'<marker id="ah-{c[1:]}" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0,0 L10,4 L0,8 z" fill="{c}"/></marker>' for c in colors)
band(20,130,"바깥","#9CA3AF"); band(165,130,"입구","#3B82F6"); band(310,120,"플랫폼","#6366F1")
band(445,235,"도메인 서비스 14개  ─  전부 게이트웨이 뒤에 있고 바깥에서 직접 닿을 수 없음","#22C55E")
band(700,145,"데이터","#F97316"); band(860,125,"관측  ─  observability 프로파일을 켤 때만","#A855F7")
box(120,60,220,70,"ext","브라우저","React 프론트 · localhost:5173")
box(120,200,220,70,"fut","nginx","예정 · 정적 파일 + /api 프록시",dash=True)
box(520,200,300,70,"edge","gateway-server  :8080","JWT 검증 · 라우팅 · 헤더 주입")
box(30,345,110,70,"ext","GitHub","paw-trail/config")
box(180,345,280,70,"plat","config-server  :8888","설정 저장소를 읽어 내려 줌")
box(1000,345,280,70,"plat","eureka-server  :8761","이름 → 주소 장부")
dom=[("auth",8081,True),("user",8082,True),("pet",8083,True),("place",8084,True),("policy",8085,True),("verdict",8086,False),("search",8087,True),
     ("ingest",8088,True),("extract",8089,False),("congestion",8090,False),("route",8091,False),("report",8092,True),("notification",8093,True),("review",8094,True)]
gx,gy,gw,gh,gap=340,485,160,46,12; pos={}
for i,(n,p,db) in enumerate(dom):
    r,c=divmod(i,7); x=gx+c*(gw+gap); y=gy+r*(gh+gap+14); pos[n]=(x,y)
    rect(x,y,gw,gh,"dom" if db else "domn",r=8); text(x+gw/2,y+19,n,14,"bold"); text(x+gw/2,y+36,f":{p}"+("" if db else " · DB 없음"),11,fill="#4B5563")
ax,ay=pos["auth"]
box(40,485,220,46,"ext","Google OAuth","소셜 로그인 · auth 만",dash=True,tsize=13)
box(40,557,220,46,"ext","Gmail SMTP","인증 코드 메일 · auth 만",dash=True,tsize=13)
arrow(ax,ay+18,262,508,"#9CA3AF",dash=True); arrow(ax,ay+34,262,580,"#9CA3AF",dash=True)
text(1532,630,"진한 초록 = 자기 DB 있음  ·  연한 초록 = 무상태",12,anchor="end",fill="#4B5563")
text(1532,650,"지금 컨테이너로 도는 것은 auth 뿐 · 나머지는 각자 IntelliJ 로 띄움",12,anchor="end",fill="#166534")
box(120,740,360,80,"data","PostgreSQL  :5432","DB 10개 · 계정 10개 · 자기 DB 만 접속")
box(560,740,260,80,"data","Redis  :6379","리프레시 토큰 · 인증 코드 · 캐시")
box(900,740,560,80,"data","Kafka  :9092 / :29092","토픽 6개 + .dlq 6개  ·  account.created · place.updated · …")
box(120,900,250,65,"obs","Prometheus  :9090","지표를 긁어 감"); box(410,900,250,65,"obs","Loki  :3100","로그")
box(700,900,250,65,"obs","Zipkin  :9411","추적"); box(990,900,250,65,"obs","Grafana  :3000","셋을 한 화면에")
arrow(230,130,230,200,"#3B82F6","① 모든 요청 (쿠키에 JWT)",lx=245,ly=170,anchor="start")
arrow(340,235,520,235,"#9CA3AF","예정",dash=True,lx=430,ly=228)
arrow(340,95,520,215,"#3B82F6","지금은 8080 으로 직접",lx=440,ly=138)
arrow(820,235,1000,365,"#6366F1",'② 주소 조회  "place 어디 있어?"',lx=930,ly=290)
arrow(670,270,670,485,"#22C55E","③ 라우팅  +  X-User-Id · X-User-Role 헤더",lx=690,ly=428,w=3,anchor="start")
arrow(180,380,140,380,"#6366F1"); text(160,372,"읽음",11,fill="#6366F1")
arrow(320,415,320,470,"#6366F1","기동 시 설정 받음",lx=390,ly=432)
arrow(1140,485,1140,415,"#6366F1","등록 · 하트비트",lx=1215,ly=432)
arrow(660,340,460,340,"#6366F1",dash=True); text(560,332,"게이트웨이도 설정을 받음",11,fill="#6366F1")
gb=603
arrow(420,gb,420,740,"#F97316","JPA · Flyway",lx=480,ly=690); arrow(690,gb,690,740,"#F97316","TTL 값",lx=730,ly=690)
arrow(1100,gb,1100,740,"#F97316","이벤트 발행 (Outbox)",lx=1190,ly=684); arrow(1300,740,1300,gb,"#F97316","이벤트 소비 (Inbox)",lx=1390,ly=684)
arrow(245,900,245,835,"#A855F7"); text(245,833,"/actuator/prometheus 를 긁어 감",11,fill="#A855F7")
arrow(535,gb,535,900,"#A855F7","로그 전송 (dev · prod 만)",lx=470,ly=855,dash=True); arrow(825,gb,825,900,"#A855F7","추적 전송",lx=870,ly=855,dash=True)
svg=f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">
<defs>{defs}</defs><rect width="{W}" height="{H}" fill="#FFFFFF"/>
{chr(10).join(out)}
<text x="{W-30}" y="{H-14}" font-size="11" text-anchor="end" fill="#9CA3AF">함께하개 (paw-trail) 전체 구조 · 층 · 2026.09</text>
</svg>'''
open("architecture-layers.svg","w",encoding="utf-8").write(svg)
import cairosvg; cairosvg.svg2png(url="architecture-layers.svg",write_to="architecture-layers.png",output_width=2400); print("layers ok")
