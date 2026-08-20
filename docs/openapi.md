# 四个端点的 OpenAPI

四个端点的契约由代码生成并被测试钉住。没有手写的 OpenAPI 文件。

```
POST /api/runs
GET  /api/runs
GET  /api/runs/{runId}
GET  /api/v1/health
```

启动应用进程后（例如 `./mvnw spring-boot:run`）：

- 规格：http://127.0.0.1:8080/v3/api-docs
- swagger-ui：http://127.0.0.1:8080/swagger-ui.html

文档里的状态码必须能在 MVC 测试里找到对应观察（202、409、404、503 均已钉住）。7 条端点行为在真实 HTTP 栈上被钉住。只覆盖这四个端点里被观察到的行为。

## 暴露面是限制

API 当前无鉴权。应用进程会一并提供 `/v3/api-docs` 与 swagger-ui。这与「单用户自托管、不要直接暴露到公网」是同一条限制，不是能力。

`./scripts/up.sh` 把主机 `8080` 接到 Vue 与 nginx。nginx 只反代 `/api/` 与 `/actuator/`，**不**把 `/v3/api-docs` 或 swagger-ui 挂到这条控制台端口上。
