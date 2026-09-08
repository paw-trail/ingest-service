// 파이프라인 본체는 Jenkins 공유 라이브러리에 있습니다.
// 이 파일에서는 파라미터 세 개만 채웁니다.
//
//   serviceName  서비스명 (레포명과 동일하게)
//   deployNode   배포 노드. edge / core / app 중 하나 (README 분류표 참고)
//   instances    띄울 인스턴스 개수

@Library('pawtrail-pipeline') _

springServicePipeline(
    serviceName: 'template',
    deployNode : 'app',
    instances  : 1
)
