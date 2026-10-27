# WorkBlog 실행 이미지. 같은 폴더에 workblog.jar 가 있어야 한다(릴리스 압축 파일에 포함).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY workblog.jar /app/workblog.jar
RUN mkdir -p /app/data/files
EXPOSE 8080
ENTRYPOINT ["java", "-Xms256m", "-Xmx768m", "-jar", "/app/workblog.jar"]
