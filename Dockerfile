# 빌드는 Jenkins에서 수행하고, 이 이미지는 결과물 jar만 담습니다.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 시각을 LocalDateTime 과 timestamp(시간대 없음)로 통일했으므로
# 컨테이너 시간대를 반드시 지정해야 합니다.
# 지정하지 않으면 컨테이너 기본값인 UTC 를 따라 로컬 실행(KST)과 9시간 어긋나는데,
# timestamp 컬럼이라 DB 가 바로잡아주지 않고 오류도 나지 않습니다.
ENV TZ=Asia/Seoul

# settings.gradle 의 rootProject.name 이 jar 이름이 되므로
# 와일드카드로 두면 서비스마다 고칠 필요가 없습니다.
COPY build/libs/*.jar app.jar

# 컨테이너 메모리 상한 대비 비율로 힙을 지정합니다.
# 지정하지 않으면 JVM이 상한을 넘겨 힙을 늘리다 로그 없이 종료됩니다.
#
# user.timezone 을 함께 지정하는 이유는 이 이미지가 Alpine 기반이라
# OS 의 tzdata 패키지가 없을 수 있기 때문입니다.
# 이 옵션은 JDK 에 내장된 시간대 데이터를 쓰므로 OS 패키지에 의존하지 않습니다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Seoul"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
