package com.pawtrail.ingest.application.service;

import com.pawtrail.common.exception.CustomException;
import com.pawtrail.ingest.domain.enums.RunType;
import com.pawtrail.ingest.domain.enums.SourceType;
import com.pawtrail.ingest.domain.exception.IngestErrorCode;
import com.pawtrail.ingest.domain.model.IngestRun;
import com.pawtrail.ingest.domain.provider.PlaceItemConverter;
import com.pawtrail.ingest.domain.provider.SourceCollector;
import com.pawtrail.ingest.domain.repository.IngestRunRepository;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집 실행을 만듭니다.
 *
 * 실제 수집은 여기서 하지 않습니다.
 * 이 메서드가 커밋된 뒤에 실행기가 비동기로 이어받습니다.
 * 나누는 이유는 두 가지입니다.
 *
 * 트랜잭션 안에서 비동기 작업을 시작하면 그쪽이 아직 커밋되지 않은 실행 기록을
 * 식별자로 읽으려다 못 찾습니다.
 *
 * 그리고 같은 객체 안에서 자기 메서드를 부르면 스프링의 비동기 프록시가 걸리지 않아
 * 조용히 같은 스레드에서 돌아 버립니다.
 */
@Slf4j
@Service
public class IngestTriggerService {

    private final IngestRunRepository ingestRunRepository;
    private final List<SourceCollector> collectors;
    private final List<PlaceItemConverter> converters;

    public IngestTriggerService(
            IngestRunRepository ingestRunRepository,
            List<SourceCollector> collectors,
            List<PlaceItemConverter> converters) {

        this.ingestRunRepository = ingestRunRepository;
        this.collectors = collectors;
        this.converters = converters;
    }

    /**
     * 그 실행 종류를 실제로 돌릴 구현이 있는지 봅니다.
     *
     * 실행기가 셋이 되면서 보는 대상이 갈렸습니다.
     *
     * <pre>
     * FULL · INCREMENTAL   SourceCollector    바깥에서 받아 우리 표에 담음
     * LINK                 SourceCollector    우리 표를 읽어 넘김.  담은 소스만 해당하므로 같음
     * DIRECT               PlaceItemConverter 파일을 읽어 바로 보냄.  수집기가 없음
     * </pre>
     *
     * 수집기만 보던 때는 그것으로 충분했습니다.
     * 앞의 셋은 전부 원본을 담는 소스라 수집기가 있었기 때문입니다.
     * 원본을 담지 않는 소스에 DIRECT 가 생기면서 그 전제가 깨졌습니다.
     *
     * 검사를 건너뛰지 않는 것이 중요합니다.
     * 건너뛰면 변환기를 만들지 않은 채로도 202 와 실행 식별자가 나가고,
     * 실행 기록을 열어 봐야 실패라는 것을 알게 됩니다.
     * 그리고 넘기기 실행기가 "트리거가 이미 확인한다" 는 전제 위에서
     * 같은 검사를 한 번 더 하고 있어, 여기가 비면 두 겹이 한 겹이 됩니다.
     */
    private boolean hasImplementation(SourceType source, RunType runType) {
        if (runType == RunType.DIRECT) {
            return converters.stream().anyMatch(converter -> converter.source() == source);
        }
        return collectors.stream().anyMatch(collector -> collector.source() == source);
    }

    /**
     * 실행을 만들고 식별자를 돌려줍니다.
     *
     * 부를 구현이 없으면 실행을 만들지 않고 거절합니다.
     * 만들어 두고 곧바로 실패로 마감하면 아무 일도 안 한 행이 이력에 남고,
     * 그것을 재개 대상으로 착각할 여지가 생깁니다.
     *
     * 다만 그 검사는 실행 조합을 전부 본 뒤에 합니다.
     * 무엇을 보는지는 실행 종류마다 다릅니다. 아래 hasImplementation 을 보십시오.
     *
     * 증분 수집도 같은 자리에서 거절합니다.
     * 아직 만들지 않은 기능인데 막지 않으면 조용히 전량 수집이 돌아
     * 이틀치 호출 허용량을 통째로 씁니다.
     * 부르는 쪽에서는 증분을 눌렀는데 이틀이 걸리는 것으로만 보여
     * 그것이 버그라는 것을 알아채기 어렵습니다.
     *
     * 같은 소스가 실행 중이면 거절합니다. 방어가 두 겹입니다.
     *
     * 먼저 조회로 걸러 냅니다. 대부분은 여기서 막히고 응답도 자연스럽습니다.
     * 그러나 조회와 저장 사이가 비어 있어 두 요청이 같은 순간에 들어오면
     * 둘 다 없음을 보고 지나갑니다.
     * 그래서 저장 시점에 유일 인덱스가 한 번 더 막습니다.
     *
     * 조회만 두거나 제약만 두면 안 됩니다.
     * 조회만 두면 그 틈으로 같은 소스가 두 번 돌아 호출 허용량을 두 배로 쓰고,
     * 제약만 두면 흔한 경우까지 예외를 만들어 잡는 모양이 됩니다.
     */
    @Transactional
    public UUID startRun(SourceType source, RunType runType) {
        if (runType == RunType.INCREMENTAL && !source.supportsIncremental()) {
            // 소스마다 증분의 값어치가 다릅니다.
            //
            // 아껴야 하는 자원은 공공데이터 호출 허용량인데 그것이 큰 소스는 하나뿐입니다.
            // 고캠핑은 한 번에 전량이 오고 문화정보원은 파일을 읽어 부를 것이 없습니다.
            // 그 둘에서 증분은 전량과 결과가 같습니다.
            //
            // 받아 주고 안에서 전량을 도는 방법도 있지만 그러면 같은 일을 두 이름으로
            // 부르게 되고, 코드만 보아서는 그 구별이 되지 않습니다.
            // 조용히 전량이 돌던 예전 상태로 되돌아가는 셈입니다.
            log.info("이 소스는 증분 수집을 지원하지 않습니다. source={}", source);
            throw new CustomException(IngestErrorCode.RUN_TYPE_NOT_SUPPORTED);
        }

        if (runType == RunType.LINK && !source.isStoredAsRawDocument()) {
            // 넘길 문서가 아예 없는 소스임
            //
            // 원본을 우리 표에 담지 않고 장소 서비스로 바로 가기로 한 소스가 하나 있음
            // 그 소스로 이 실행을 부르면 읽을 것이 없어 0 건으로 조용히 끝남
            // 그러면 나중에 "왜 안 붙었지" 를 찾을 때 실행 기록만 보고는 알 수 없음
            //
            // 새 표시를 두지 않고 원본을 담는지로 판단함
            // 담지 않으면 넘길 것도 없다는 관계가 그대로 성립하므로
            // 둘을 따로 두면 한쪽만 고쳐 어긋날 자리가 생김
            log.info("이 소스는 원본을 담지 않아 넘길 문서가 없습니다. source={}", source);
            throw new CustomException(IngestErrorCode.RUN_TYPE_NOT_SUPPORTED);
        }

        if (runType == RunType.DIRECT && source.isStoredAsRawDocument()) {
            // 바로 보내면 안 되는 소스임
            //
            // 위 검사와 정확히 반대임
            // 원본을 담는 소스는 담는 것과 넘기는 것이 두 단계로 갈려 있어
            // 받아 오기는 FULL 이나 INCREMENTAL 로, 넘기기는 LINK 로 부름
            // 그 소스로 이 실행을 부르면 파일을 읽는 자리가 아예 없음
            //
            // 표시를 하나로 두는 것이 중요함
            // "담으면 LINK · 안 담으면 DIRECT" 라는 관계가 그대로 성립하므로
            // 값을 따로 두면 한쪽만 고쳐 어긋날 자리가 생김
            log.info("이 소스는 원본을 담으므로 바로 보낼 수 없습니다. source={}", source);
            throw new CustomException(IngestErrorCode.RUN_TYPE_NOT_SUPPORTED);
        }

        if (runType != RunType.DIRECT && !source.isStoredAsRawDocument()) {
            // 담지 않는 소스에 받아 오기를 부른 경우임
            //
            // FULL 과 INCREMENTAL 은 받아서 우리 표에 담는 실행인데 담을 자리가 없음
            // 수집기가 없던 동안은 앞의 검사가 대신 막아 주었으나
            // 이제 그 소스에도 실행 경로가 생겨 그 방어가 사라짐
            log.info("이 소스는 원본을 담지 않아 받아 오기를 할 수 없습니다. source={}", source);
            throw new CustomException(IngestErrorCode.RUN_TYPE_NOT_SUPPORTED);
        }

        // 조합 검사를 전부 지난 뒤에 구현이 있는지 봅니다.
        //
        // 순서가 중요합니다.
        // 이것을 먼저 두면 원본을 담지 않는 소스에 받아 오기를 부를 때
        // "지원하지 않는 소스" 로 거절됩니다. 그 소스는 지원하고 있고
        // 맞지 않는 것은 실행 종류라 이유가 틀립니다.
        // 두 코드가 HTTP 상태는 같아도 응답의 code 가 달라 부르는 쪽이 그것으로 가릅니다.
        if (!hasImplementation(source, runType)) {
            throw new CustomException(IngestErrorCode.COLLECTOR_NOT_REGISTERED);
        }

        if (ingestRunRepository.existsRunningBySource(source)) {
            throw new CustomException(IngestErrorCode.INGEST_ALREADY_RUNNING);
        }

        try {
            // 그 자리에서 반영합니다.
            //
            // 그냥 저장하면 반영이 트랜잭션이 끝날 때까지 미뤄질 수 있습니다.
            // 그러면 유일 인덱스에 부딪히는 시점도 함께 미뤄져
            // 예외가 이 try 를 지나가지 않고 밖에서 나므로 아래 변환이 걸리지 않습니다.
            // 부르는 쪽은 이미 실행 중이라는 안내 대신 정체를 알 수 없는 서버 오류를 받습니다.
            IngestRun run = ingestRunRepository.saveAndFlush(IngestRun.start(source, runType));
            log.info("수집 실행을 만들었습니다. runId={} source={} runType={}",
                    run.getId(), source, runType);
            return run.getId();

        } catch (DataIntegrityViolationException e) {
            // uq_ingest_run_running 에 부딪힘
            // 위 조회를 통과한 뒤 다른 요청이 먼저 저장한 경우임
            log.info("같은 소스의 실행이 방금 만들어졌습니다. source={}", source);
            throw new CustomException(IngestErrorCode.INGEST_ALREADY_RUNNING, e);
        }
    }
}
