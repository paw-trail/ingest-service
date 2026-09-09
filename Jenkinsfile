// 파이프라인 본체는 Jenkins 공유 라이브러리에 있습니다.
// 이 파일에서는 파라미터 세 개만 채웁니다.
//
//   serviceName  서비스명 (레포명과 동일하게)
//   deployNode   배포 노드. edge / core / app 중 하나 (README 분류표 참고)
//   instances    띄울 인스턴스 개수

@Library('pawtrail-pipeline') _

springServicePipeline(
    serviceName: 'ingest-service',

    // 노드는 app 이지만 상시 기동이 아닙니다.
    // 이 서비스는 화면이 없고 배치라, 평소에는 떠 있지 않고
    // Jenkins 잡이 수집을 부를 때만 돕니다.
    // compose 에서도 app 이 아니라 pipeline 프로파일에 있습니다.
    //
    // 공유 라이브러리가 아직 없어 이 값이 어떻게 쓰일지는 그 저장소를 만들 때 정합니다.
    // pipeline 을 노드 이름으로 받을지, app 에 두고 기동만 수동으로 둘지가 갈립니다.
    deployNode : 'app',
    instances  : 1
)
