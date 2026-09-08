# 레포 중심 그림 — 가운데에 그 레포, 주변에 직접 연결된 것만
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

# ── gateway-server
d=D(1500,760)
d.me("gw",750,380,340,100,"edge","gateway-server  :8080","JWT 검증 · 라우팅 · 헤더 주입|WebFlux · 상태 없음")
d.node("br",180,180,240,80,"ext","브라우저","쿠키에 JWT")
d.node("ng",180,380,240,80,"fut","nginx","예정",dash=True)
d.node("cf",750,120,300,80,"plat","config-server  :8888","라우트 19 · 공개키 · permit-all 9")
d.node("eu",1300,120,300,80,"plat","eureka-server  :8761","이름 → 주소")
d.node("dom",1300,380,300,110,"dom","도메인 서비스 14개","auth · user · pet · place …|헤더만 믿고 토큰은 안 봄")
d.node("zk",1300,620,300,80,"obs","Zipkin","추적이 여기서 시작됨")
d.node("au",180,620,260,80,"dom","auth-service","공개키의 짝인 개인키로 서명")
d.edge("br","r","gw","l",B,"① 모든 요청  (지금은 8080 직접)",via=[(580,180),(580,340)],lx=430,ly=170)
d.edge("ng","r","gw","l",X,"예정",dash=True)
d.edge("cf","b","gw","t",V,"기동 시 설정")
d.edge("gw","r","eu","l",V,"② \"place 어디 있어?\"",via=[(1000,380),(1000,120)],lx=1000,ly=250)
d.edge("gw","r","dom","l",G,"③ 라우팅  +  X-User-Id · X-User-Role",w=3.5,lx=980,ly=372)
d.edge("gw","b","zk","t",P,"traceId 여기서 생성",dash=True,a_pt=(850,430),via=[(850,530),(1300,530)],lx=1080,ly=522)
d.edge("au","t","gw","b",L,"같은 키 쌍 (auth 개인키 · 여기 공개키)",dash=True,via=[(180,530),(650,530)],b_pt=(650,430),lx=420,ly=522)
d.note(40,720,"바깥에서 들어온 X-User-Id 는 여기서 지워짐 · permit-all 9줄은 auth 와 같은 목록 · 401 · 403 · 404 · 503 을 직접 냄")
d.save("gateway-server","gateway-server 를 중심으로 · 직접 연결된 것만")

# ── auth-service
d=D(1500,820)
d.me("au",750,410,340,100,"dom","auth-service  :8081","가입 · 로그인 · 토큰 발급 · 탈퇴|API 16개 · 서비스 11개")
d.node("gw",180,410,240,90,"edge","gateway-server","공개키로 검증|X-User-Id 헤더 주입")
d.node("cf",750,120,300,80,"plat","config-server","JWT · 메일 · OAuth 설정")
d.node("eu",1300,120,300,80,"plat","eureka-server","등록")
d.node("pg",1300,330,300,80,"data","PostgreSQL  auth_db","account · refresh_token_log · outbox")
d.node("rd",1300,470,300,80,"data","Redis","토큰 · 인증 코드 · state  (9종)")
d.node("kf",1300,640,300,90,"data","Kafka","account.created → user|account.withdrawn → 5개 서비스")
d.node("gg",180,150,240,80,"ext","Google OAuth","소셜 로그인",dash=True)
d.node("gm",180,660,240,80,"ext","Gmail SMTP","인증 코드 메일",dash=True)
d.edge("gw","r","au","l",B,"로그인 · 가입은 토큰 없이 · /me · 탈퇴는 헤더로",lx=430,ly=395)
d.edge("cf","b","au","t",V,"기동 시 설정")
d.edge("au","r","eu","l",V,"등록",via=[(1000,410),(1000,120)],lx=1000,ly=260)
d.edge("au","r","pg","l",O,"JPA · Flyway V20~23",via=[(1000,410),(1000,330)],lx=1010,ly=340)
d.edge("au","r","rd","l",O,"TTL 값",via=[(1000,410),(1000,470)],lx=1010,ly=490)
d.edge("au","r","kf","l",O,"Outbox 로 발행  (받는 것은 없음)",via=[(1000,410),(1000,640)],lx=1010,ly=660)
d.edge("au","l","gg","r",X,"authorize · callback",dash=True,via=[(430,410),(430,150)],lx=440,ly=270)
d.edge("au","l","gm","r",X,"6자리 코드",dash=True,via=[(430,410),(430,660)],lx=440,ly=560)
d.note(40,790,"다른 도메인 서비스를 한 번도 부르지 않음 · 개인키는 환경변수, 공개키는 config 저장소 · 유일하게 자기 SecurityFilterChain 을 정의함")
d.save("auth-service","auth-service 를 중심으로 · 직접 연결된 것만")

# ── config-server
d=D(1500,700)
d.me("cs",750,350,340,100,"plat","config-server  :8888","저장소를 읽어 4계층을 겹쳐 내려 줌|자바 파일 1개")
d.node("gh",180,350,260,90,"ext","GitHub","paw-trail/config|yml 23개 · main")
d.node("all",1300,250,320,110,"dom","다른 서비스 전부","도메인 14 · gateway · eureka|기동할 때 여기로 물어봄")
d.node("eu",1300,520,300,80,"plat","eureka-server","등록만 함 (아무도 안 찾음)")
d.node("me2",750,120,340,80,"fut","이 서버의 application.yml","자기 설정은 저장소에서 못 받음 (닭-달걀)",dash=True)
d.edge("gh","r","cs","l",V,"clone-on-start · 요청마다 다시 읽음")
d.edge("all","l","cs","r",V,"GET /{서비스명}/{환경}",via=[(1000,250),(1000,350)],lx=1000,ly=290,both=False)
d.edge("cs","r","eu","l",V,"등록 · 하트비트",via=[(1000,350),(1000,520)],lx=1000,ly=445)
d.edge("me2","b","cs","t",X,"git.uri · 포트 · EUREKA_HOST · LOKI_HOST",dash=True,lx=760,ly=250)
d.note(40,660,"${환경변수} 는 치환하지 않고 문자열 그대로 내려보냄 → 비밀을 몰라도 됨 → 저장소를 공개로 둘 수 있음")
d.save("config-server","config-server 를 중심으로 · 직접 연결된 것만")

# ── eureka-server
d=D(1500,700)
d.me("eu",750,350,340,100,"plat","eureka-server  :8761","이름 → 주소 장부|자기는 장부에 안 올림")
d.node("gw",180,200,260,90,"edge","gateway-server","\"place 어디 있어?\"|lb://place-service")
d.node("dom",180,500,260,110,"dom","도메인 서비스 14개","기동할 때 등록|30초마다 하트비트")
d.node("cf",750,120,300,80,"plat","config-server","eureka-server.yml + 4계층 my-url")
d.node("cs",1300,500,300,80,"plat","config-server 도 등록","대시보드에 보이기 위해")
d.node("dash",1300,200,300,90,"obs","대시보드  :8761","등록 목록 · DS Replicas 는 비어야 정상")
d.edge("gw","r","eu","l",V,"② 조회",via=[(500,200),(500,350)],lx=500,ly=270)
d.edge("dom","r","eu","l",V,"등록 · 하트비트 · 90초 없으면 만료",via=[(500,500),(500,350)],lx=500,ly=440)
d.edge("cf","b","eu","t",V,"기동 시 설정  (my-url 이 없으면 기동 실패)")
d.edge("cs","l","eu","r",V,"등록만",via=[(1000,500),(1000,350)],lx=1000,ly=440)
d.edge("eu","r","dash","l",P,"",via=[(1000,350),(1000,200)])
d.note(40,660,"자기보호 모드를 껐음 · local 은 host.docker.internal 로, dev 는 컨테이너 IP 로 등록됨 · 피어 복제 없음 (1대)")
d.save("eureka-server","eureka-server 를 중심으로 · 직접 연결된 것만")

# ── common
d=D(1500,760)
d.me("cm",750,380,340,110,"lib","common  0.0.9","자동 설정 6개 · Flyway V1 · V2|실행되지 않는 jar")
d.node("gp",750,120,320,80,"ext","GitHub Packages","publish 로 올림 · 덮어쓰기 불가")
d.node("dom",1300,240,320,110,"dom","도메인 서비스 14개","build.gradle 한 줄로 받음|응답 형식 · 예외 · 인증 · 감사 · Outbox")
d.node("nodb",1300,520,320,90,"domn","무상태 서비스 3개","verdict · congestion · route|JPA 자동 설정만 안 켜짐")
d.node("plat",180,240,280,110,"plat","플랫폼 3개","gateway · eureka · config-server|⛔ 안 씀 — 인프라 성격")
d.node("pg",180,520,280,90,"data","PostgreSQL","outbox · processed_event 표|V1 · V2 가 만듦")
d.edge("cm","t","gp","b",L,"./gradlew publish")
d.edge("gp","r","dom","l",L,"commonVersion=0.0.9",via=[(1000,120),(1000,240)],lx=1000,ly=180)
d.edge("cm","r","dom","l",L,"자동 설정 6개 전부",via=[(1000,380),(1000,240)],lx=1010,ly=320,anchor="start")
d.edge("cm","r","nodb","l",L,"Web · Security · Async 만",via=[(1000,380),(1000,520)],lx=1010,ly=460,anchor="start")
d.edge("plat","r","cm","l",X,"의존하지 않음",dash=True,via=[(500,240),(500,380)],lx=500,ly=310)
d.edge("cm","l","pg","r",O,"Flyway V1 · V2 (jar 안에)",via=[(500,380),(500,520)],lx=500,ly=460)
d.note(40,720,"넣는 기준 = \"도메인 서비스가 전부 쓰는 것\" · 토픽 이름 · 도메인 에러 코드는 넣지 않음 · 서비스가 같은 타입의 Bean 을 정의하면 물러남")
d.save("common","common 을 중심으로 · 직접 연결된 것만")

# ── config (저장소)
d=D(1500,760)
d.me("cf",750,380,340,110,"lib","paw-trail/config","yml 23개 · 4계층|코드 없음 · 공개 저장소")
d.node("cs",180,380,280,90,"plat","config-server","요청마다 여기를 읽음")
d.node("all",180,140,280,100,"dom","서비스 17개","각자 application.yml 은 3줄|나머지는 여기서")
d.node("env",750,120,340,80,"ext","환경변수","${DB_HOST} · ${SERVICE_DB_PASSWORD} · ${AUTH_…}",dash=True)
d.node("gw",1300,200,300,90,"edge","gateway-server.yml","라우트 19 · 공개키 · permit-all 9|293줄")
d.node("au",1300,380,300,90,"dom","auth-service.yml","JWT · 메일 · OAuth · permit-all 9|238줄")
d.node("l3",1300,560,300,100,"plat","application-{env}.yml","local · dev · prod|주소만 갈림 · prod 는 거의 TODO")
d.edge("cf","l","cs","r",V,"git clone · pull")
d.edge("cs","t","all","b",V,"GET /{서비스명}/{환경}")
d.edge("env","b","cf","t",X,"값은 여기 없음 — 자리만",dash=True,lx=760,ly=250)
d.edge("cf","r","gw","l",V,"2계층",via=[(1000,380),(1000,200)],lx=1000,ly=280)
d.edge("cf","r","au","l",V,"2계층",lx=1010,ly=372)
d.edge("cf","r","l3","l",V,"3계층",via=[(1000,380),(1000,560)],lx=1000,ly=480)
d.note(40,720,"1 application.yml < 2 {서비스}.yml < 3 application-{env}.yml < 4 {서비스}-{env}.yml — 숫자가 클수록 이김 · 4계층 실사례는 eureka 둘뿐")
d.save("config","config 저장소를 중심으로 · 직접 연결된 것만")

# ── infra
d=D(1500,800)
d.me("inf",750,400,340,110,"lib","paw-trail/infra","docker-compose.yml · 컨테이너 12개|프로파일 6개")
d.node("ij",180,180,280,100,"ext","IntelliJ 로 띄운 서비스","지금 고치는 것|localhost:5432 · :29092 · :8888")
d.node("db",180,400,280,90,"data","db","postgres — DB 10개 · 계정 10개")
d.node("inf2",180,620,280,90,"data","infra","kafka · redis")
d.node("plat",1300,180,300,100,"plat","platform","config-server · eureka-server|gateway-server")
d.node("app",1300,400,300,90,"dom","app","auth-service  (지금은 하나)")
d.node("obs",1300,620,300,100,"obs","observability · tools","prometheus · loki · zipkin · grafana|kafka-ui :9000")
d.edge("inf","l","db","r",O,"항상")
d.edge("inf","l","inf2","r",O,"항상",via=[(500,400),(500,620)],lx=500,ly=520)
d.edge("inf","r","plat","l",V,"항상",via=[(1000,400),(1000,180)],lx=1000,ly=290)
d.edge("inf","r","app","l",G,"COMPOSE_PROFILES 에 app 을 넣을 때",lx=1010,ly=392)
d.edge("inf","r","obs","l",P,"필요할 때만",via=[(1000,400),(1000,620)],lx=1000,ly=520)
d.edge("ij","b","db","t",X,"localhost 로 붙음  (컨테이너 안에서는 postgres)",dash=True,lx=190,ly=300)
d.note(40,760,"--profile 을 명령에 붙이면 .env 값이 대체됨 (더해지지 않음) · .env 는 커밋 안 됨 · Kafka 는 볼륨이 없어 down 하면 토픽이 사라짐 (재생성 멱등)")
d.save("infra","infra 를 중심으로 · 직접 연결된 것만")

# ── service-template
d=D(1500,820)
d.me("st",750,410,340,110,"dom","새 도메인 서비스","service-template 을 복제해 만듦|4계층 · common · Outbox")
d.node("gw",180,410,260,90,"edge","gateway-server","라우트를 열어야 닿음")
d.node("cf",750,130,300,90,"plat","config 저장소","{서비스명}.yml 을 만들어야 뜸|포트 · DB · outbox 스위치")
d.node("eu",1300,130,300,80,"plat","eureka-server","기동하면 자동 등록")
d.node("cm",180,150,260,90,"lib","common","gradle 한 줄 · 자동 설정 6개")
d.node("pg",1300,330,300,90,"data","PostgreSQL","자기 DB 하나 · Flyway V20~")
d.node("kf",1300,530,300,100,"data","Kafka","발행은 Outbox · 소비는 Inbox|토픽은 infra 스크립트에 먼저")
d.node("oth",1300,720,300,80,"dom","다른 도메인 서비스","/internal 로 서로 부름")
d.node("inf",180,680,260,90,"data","infra compose","완성되면 app 프로파일에 등록")
d.edge("gw","r","st","l",B,"③  X-User-Id · X-User-Role 헤더")
d.edge("cf","b","st","t",V,"기동 시 설정")
d.edge("st","r","eu","l",V,"등록",via=[(1000,410),(1000,130)],lx=1000,ly=270)
d.edge("cm","r","st","l",L,"의존성",via=[(500,150),(500,410)],lx=500,ly=280)
d.edge("st","r","pg","l",O,"JPA",via=[(1000,410),(1000,330)],lx=1010,ly=340)
d.edge("st","r","kf","l",O,"이벤트",via=[(1000,410),(1000,530)],lx=1010,ly=480,both=True)
d.edge("st","r","oth","l",G,"/internal · 헤더 그대로 전달",via=[(1000,410),(1000,720)],lx=1010,ly=640,both=True)
d.edge("st","b","inf","t",X,"이미지로 구운 뒤",dash=True,via=[(750,600),(180,600)],lx=470,ly=592)
d.note(40,790,"복제 후 할 일 8개 (settings · gradle.properties · yml · Dockerfile · Jenkinsfile · V20 · README) · DB 없는 서비스는 데이터 블록을 통째로 지움")
d.save("service-template","새 서비스를 중심으로 · 직접 연결된 것만")

# ── user-service
d=D(1640,900)
d.me("us",800,440,360,100,"dom","user-service  :8082","프로필 · 즐겨찾기 · 방문 · 일정 · 요약|API 20개 · 서비스 7개")
d.node("gw",180,440,240,90,"edge","gateway-server","토큰 검증|X-User-Id 헤더 주입")
d.node("cf",800,120,300,80,"plat","config-server","포트 · DB · S3 · LLM 설정")
d.node("eu",1400,120,300,80,"plat","eureka-server","등록 · lb:// 해석")
d.node("pg",1400,300,300,90,"data","PostgreSQL  user_db","프로필 · 즐겨찾기 · 방문|일정 · 요약  (표 5개)")
d.node("rd",1400,470,300,80,"data","Redis","최근 장소 · 요약 쿨다운 · 한도")
d.node("s3",180,150,240,80,"ext","AWS S3","프로필 사진",dash=True)
d.node("ai",180,700,240,80,"ext","OpenAI","하루 요약 문장",dash=True)
d.node("kf",800,760,300,90,"data","Kafka","account.created  수신|account.withdrawn  수신")
d.node("pl",1400,690,300,110,"domn","place · verdict · review","이름 · 사진 · 좌표|판정 · 준비물 · 평점|아직 없음 — 스텁으로 흉내",dash=True)
d.edge("gw","r","us","l",B,"공개 API 18개 · 무인증 경로 0",lx=470,ly=422)
d.edge("cf","b","us","t",V,"기동 시 설정")
d.edge("us","r","eu","l",V,"등록",via=[(1080,440),(1080,120)],lx=1160,ly=205)
d.edge("us","r","pg","l",O,"JPA · Flyway V20~21",via=[(1080,440),(1080,300)],lx=1160,ly=378)
d.edge("us","r","rd","l",O,"키 3종",via=[(1080,440),(1080,470)],lx=1160,ly=462)
d.edge("us","r","pl","l",G,"lb:// 로 부름",via=[(1080,440),(1080,690)],lx=1160,ly=596)
d.edge("us","b","kf","t",O,"Inbox 로 수신  (발행하지 않음)",lx=812,ly=640,anchor="start")
d.edge("us","l","s3","r",X,"presigned URL",dash=True,via=[(470,440),(470,150)],lx=480,ly=280,anchor="start")
d.edge("us","l","ai","r",X,"gpt-5.6-luna · 20초",dash=True,via=[(470,440),(470,700)],lx=480,ly=600,anchor="start")
d.note(40,858,"프로필을 만드는 API 가 없음 — account.created 로 생기고 account.withdrawn 으로 지워짐 · 소프트 딜리트는 user_profile 하나뿐 · 이벤트를 발행하지 않아 outbox 는 비어 있음")
d.note(40,880,"목록 조회 하나가 place · verdict · review 셋을 부름 — place 가 실패하면 목록 전체가 실패하고 나머지 둘은 그 값만 비움")
d.save("user-service","user-service 를 중심으로 · 직접 연결된 것만")
