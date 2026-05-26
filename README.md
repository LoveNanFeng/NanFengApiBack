# NanFengAPI 后端

> **项目仓库地址**
>
> 主仓库：[LoveNanFeng/NanFengAPI](https://github.com/LoveNanFeng/NanFengAPI)
>
> 前端仓库：[LoveNanFeng/NanFengApiFront](https://github.com/LoveNanFeng/NanFengApiFront)
>
> 后端仓库：[LoveNanFeng/NanFengApiBack](https://github.com/LoveNanFeng/NanFengApiBack)
>
> **重要：数据库 SQL 文件路径**
>
> `src/main/resources/db/schema.sql`
>
> 首次部署请先导入该 SQL 文件，再启动后端服务。

这是 NanFengAPI 计费系统后端，基于 Spring Boot 3、Java 17、MyBatis-Plus、MySQL、Redis 构建，提供用户登录注册、接口管理、密钥管理、开放接口网关、套餐计费、支付配置、公告、友情链接、调用日志和首页统计等能力。

## 项目地址

| 项目 | 仓库地址 | 说明 |
| --- | --- | --- |
| 主仓库 | [LoveNanFeng/NanFengAPI](https://github.com/LoveNanFeng/NanFengAPI) | NanFengAPI 项目主仓库，项目说明和部署文档都在此仓库 |
| 前端仓库 | [LoveNanFeng/NanFengApiFront](https://github.com/LoveNanFeng/NanFengApiFront) | NanFengAPI 前端项目，主业务应用在 `vben-admin/playground` |
| 后端仓库 | [LoveNanFeng/NanFengApiBack](https://github.com/LoveNanFeng/NanFengApiBack) | 当前后端项目，提供 `/api` 和 `/open/v1` 服务 |

| 配置项 | 默认值 |
| --- | --- |
| 后端项目 | `NanFengAPI 后端` |
| 前端项目 | `NanFengAPI 前端` |
| 默认接口前缀 | `/api` |
| 开放接口前缀 | `/api/open/v1` |
| 默认服务端口 | `8080` |
| 默认本地地址 | `http://localhost:8080/api` |
| 数据库 SQL 文件 | `src/main/resources/db/schema.sql` |

## 技术栈

- Java 17
- Spring Boot 3.3.5
- Spring Security
- MyBatis-Plus
- MySQL 8+
- Redis
- JWT
- Hutool
- Spring Mail
- Alibaba DYPNS SDK

## 目录说明

```text
backend/
  src/main/java/com/nanfeng/billing/      后端业务代码
  src/main/resources/application.yml      主配置文件
  src/main/resources/db/schema.sql        数据库初始化 SQL
  uploads/                                本地上传文件目录
  data/                                   运行期数据目录
  pom.xml                                 Maven 项目配置
```

## 数据库初始化

数据库 SQL 文件在：

```text
src/main/resources/db/schema.sql
```

导入示例：

```bash
mysql -u root -p nanfeng_api_billing < src/main/resources/db/schema.sql
```

如果数据库不存在，可以先创建：

```sql
CREATE DATABASE nanfeng_api_billing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`schema.sql` 包含表结构和基础数据，例如用户、角色、菜单、站点配置、接口配置、套餐配置、支付配置等。

## 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8+
- Redis 6+

## 配置说明

主配置文件：

```text
src/main/resources/application.yml
```

常用环境变量：

```env
SERVER_PORT=8080

DB_HOST=localhost
DB_PORT=3306
DB_NAME=nanfeng_api_billing
DB_USER=root
DB_PASSWORD=123456
DB_SSL=false

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DATABASE=0
REDIS_PASSWORD=

JWT_SECRET=请替换为生产环境随机密钥
JWT_COOKIE_SECURE=false
```

生产环境请务必修改：

- `JWT_SECRET`
- `DB_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_COOKIE_SECURE=true`，如果前端部署在 HTTPS 下

## 本地启动

进入后端目录：

```bash
cd backend
```

编译：

```bash
mvn clean package -DskipTests
```

启动：

```bash
mvn spring-boot:run
```

或运行打包后的 Jar：

```bash
java -jar target/api-billing-backend-0.0.1-SNAPSHOT.jar
```

启动成功后访问：

```text
http://localhost:8080/api
```

## 打包命令

```bash
cd backend
mvn clean package -DskipTests
```

打包产物：

```text
target/api-billing-backend-0.0.1-SNAPSHOT.jar
```

## 前端适配

前端开发环境默认代理到：

```text
http://localhost:8080/api
```

前端仓库或前端目录中的配置应保持：

```env
VITE_GLOB_API_URL=/api
```

开发环境中，前端 Vite 会将 `/api` 和 `/open` 代理到后端。

## 部署提示

1. 安装并启动 MySQL、Redis。
2. 创建数据库 `nanfeng_api_billing`。
3. 导入 SQL：`src/main/resources/db/schema.sql`。
4. 配置生产环境变量。
5. 执行 `mvn clean package -DskipTests`。
6. 使用 `java -jar target/api-billing-backend-0.0.1-SNAPSHOT.jar` 启动。
7. 配置 Nginx，将 `/api` 和 `/open` 转发到后端。

Nginx 示例：

```nginx
location /api/ {
  proxy_pass http://127.0.0.1:8080/api/;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}

location /open/ {
  proxy_pass http://127.0.0.1:8080/api/open/;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
}
```

如果部署在反向代理后面，并且需要识别真实客户端 IP，请在 `application.yml` 中配置可信代理：

```yaml
security:
  client-ip:
    trusted-proxies:
      - 127.0.0.1
      - 你的 Nginx 或网关 IP
```

## 常用命令

```bash
# 编译
mvn -q -DskipTests compile

# 打包
mvn clean package -DskipTests

# 本地启动
mvn spring-boot:run

# 运行 Jar
java -jar target/api-billing-backend-0.0.1-SNAPSHOT.jar
```

## 注意事项

- `src/main/resources/db/schema.sql` 是初始化数据库的关键文件，上传 GitHub 时请保留。
- 生产环境不要使用默认 `JWT_SECRET`。
- 生产环境不要使用默认数据库密码。
- Redis 必须可用，开放接口额度、QPS、验证码、登录限制等能力依赖 Redis。
- 如果修改接口、密钥、扣费规则或用户状态，系统会清理开放接口配置缓存。
