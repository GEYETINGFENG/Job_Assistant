# ---------- ① 多阶段构建：builder 阶段需要 JDK + Maven + 源码 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom.xml 下载依赖：依赖单独成层，只改代码时不会重新下载
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 测试依赖 Testcontainers，在宿主机运行 ./mvnw test，镜像构建时跳过
RUN mvn -B -q package -DskipTests

# ---------- ② 精简基础镜像：运行阶段只需要 JRE + jar ----------
FROM eclipse-temurin:17-jre-alpine

# ③ 非 root 用户：容器被攻破时限制破坏半径
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder --chown=app:app /build/target/JobAssistant-*.jar app.jar
USER app

EXPOSE 8080

# 密钥不写进镜像，全部在运行时通过环境变量注入。
# exec 形式让 java 成为 PID 1，可以直接收到 SIGTERM（为优雅停机做准备）。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
