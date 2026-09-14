# 契衡合同管理系统（ContractHub）

> **把 Word 合同模板变成"填报 → 生成 → 定稿 → 归档"的在线流程。**
> 上传 Word 母版 → 配置字段 → 业务员在线填报 → 一键生成 Word/PDF → 全文确认定稿 → 登记签署件归档，全程留痕、可审计。

面向企业内部（尤其**内网离线环境**）的合同管理系统：单个 jar 部署，不依赖外网、不需要前端构建、无公开注册入口。
当前版本 **v1.0.0**，Java 21 + Spring Boot 3.5。

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [使用流程](#使用流程)
- [业务规则](#业务规则)
- [项目结构](#项目结构)
- [接口一览](#接口一览)
- [开发与测试](#开发与测试)
- [部署与运维](#部署与运维)
- [安全与数据](#安全与数据)
- [常见问题](#常见问题)
- [文档](#文档)
- [许可](#许可)

---

## 功能特性

### 模板中心：发布式版本管理
- 上传 **DOCX 母版**，系统自动扫描 `{{字段名}}` 占位符并生成字段配置。
- 母版里没有占位符时，可用两种方式**自动识别待填位置**：① 把待填文字设成指定颜色；② 连续 3 个以上下划线（`___`）。
- **母版是字段的唯一来源**：上传母版时字段配置自动与之对齐（新增占位符自动补字段、已删占位符自动删字段，并顺手修复被删字段弄坏的引用：金额大写来源、日期先后比较、分组归属）；也可在「字段配置」里点【按母版同步字段】手工对齐。
- 字段配置支持：中文名、类型（文本/多行/日期/金额/整数/下拉/是否/金额大写自动计算）、必填、长度、数值范围、默认值、分组排序。
- **发布式版本**：已发布版本不可原地修改；改动要新建版本。版本号自动分配并**复用被删除版本腾出的号**。版本可命名（纯元数据，不影响已完成的试填确认）。
- 发布前必须完成 **试填 → 查看试填 PDF → 确认试填**（指纹绑定当时的母版 + 字段配置，任一处改动都会失效重来）。

### 在线填报与生成
- 按字段类型渲染控件（日期选择器、下拉、金额校验、只读计算项…），**改动后关闭页面会先自动保存为草稿**，不会因为误点弹窗外面丢掉填写内容。
- 生成走**异步任务队列**（默认 1 个 worker），Word 由母版替换占位符产出，PDF 由 **LibreOffice** 转换，失败会给出明确错误码，不会伪造可下载文件。
- 生成 → **查看全文 PDF** → 确认全文并定稿：确认凭据有时效（默认 30 分钟），定稿后的文件与修订快照不可再变。
- 合同状态机：`草稿 → 已定稿 → 已签署`，可 `作废`，可「派生新草稿」，模板升级后可「升级到新版本」。

### 权限与审计
- 三类账号：`ADMIN` / `CONTRACT_MAINTAINER` / `USER`；对象级权限校验（自己的合同、模板维护权限、管理员专属操作）。
- **没有公开注册和网页初始化后门**：首个管理员只能用命令行在服务器本地创建。
- 登录安全：BCrypt 密码哈希、会话 Cookie、CSRF 令牌、按账户与来源双重失败限速、首次登录强制改密、致命密码黑名单。
- **审计日志追加式保留**：模板/合同/账号的创建、修改、发布、试填、定稿、下载、作废、彻底删除等全部留痕（含删除原因与 `contract_no`），不记录密码与完整填报内容。

### 部署友好（内网）
- **单 jar**：`java -jar ContractHub-1.0.0.jar` 即可运行；默认用内嵌 H2 文件库，**零配置即可体验**。
- 正式使用接 **PostgreSQL 16**（Flyway 自动建表/升级），`一键启动契衡系统.bat` 封装了 Docker + 启动 + 健康检查。
- 上传文件与生成件存放在 `STORAGE_ROOT`（默认 `./data/files`），孤儿文件自动回收（默认保留 24 小时）。
- 前端是零构建的原生 JS 单页应用，直接由后端 `static/` 提供。

---

## 技术栈

| 层 | 选型 |
| --- | --- |
| 运行时 | Java 21（Spring Boot 3.5.5） |
| 数据库 | PostgreSQL 16（推荐）/ H2 文件库（默认，零配置） |
| 迁移 | Flyway（`V1`～`V5`） |
| 文档处理 | Apache POI 5.4.1（DOCX 扫描/改写）、PDFBox 3.0.5（PDF 校验）、LibreOffice（DOCX→PDF） |
| 安全 | spring-security-crypto（BCrypt）、BouncyCastle |
| 前端 | 原生 JS 单页（`static/app.js`、`app.css`、`index.html`），无构建步骤 |
| 构建 | Maven（Spring Boot Maven Plugin） |

---

## 快速开始

### 方式一：直接运行 jar（最快，无需 Docker）

```bash
java -jar target/ContractHub-1.0.0.jar
```

默认使用 H2 文件库 `./data/qiheng` 与存储目录 `./data/files`，浏览器打开 <http://127.0.0.1:8080/>。

### 方式二：PostgreSQL（推荐正式使用）

```bash
# 1. 起一个 PostgreSQL（示例用 Docker，仅本机可访问）
docker run -d --name qiheng-postgres \
  -e POSTGRES_DB=qiheng -e POSTGRES_USER=qiheng -e POSTGRES_PASSWORD=改成你自己的强密码 \
  -p 127.0.0.1:5432:5432 -v qiheng-postgres:/var/lib/postgresql/data postgres:16

# 2. 启动应用（Flyway 会自动建表）
set DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/qiheng
set DATABASE_USERNAME=qiheng
set DATABASE_PASSWORD=改成你自己的强密码
set STORAGE_ROOT=./data/files
set LIBREOFFICE_PATH=C:\Program Files\LibreOffice\program\soffice.exe
java -jar target/ContractHub-1.0.0.jar
```

Windows 上更省事：双击 **`一键启动契衡系统.bat`**（启动 Docker 里的 `qiheng-postgres` → 等数据库真正可用 → 起服务 → 打开浏览器），停止用 `停止契衡系统.bat`。

### 方式三：从源码构建

```bash
mvn package            # 产物：target/ContractHub-1.0.0.jar
mvn spring-boot:run    # 或直接跑
```

### 首次创建管理员（必须）

系统没有网页初始化入口，首个管理员只能在服务器本地用命令行创建（密码至少 6 位）：

```bash
java -jar target/ContractHub-1.0.0.jar --init-admin \
  --username=admin --display-name=系统管理员 --password=请替换为随机强密码
```

忘记管理员密码时（会重置该账号，不影响业务数据）：

```bash
java -jar target/ContractHub-1.0.0.jar --recover-admin \
  --username=admin --display-name=系统管理员 --password=新的强密码
```

### 让局域网内其他电脑访问

应用**默认只监听 `127.0.0.1`**（安全默认值）。同事要访问就在启动脚本/环境变量里加：

```bat
set BIND_ADDRESS=0.0.0.0
```

并在 Windows 防火墙放行 TCP 8080，然后访问 `http://<这台电脑的IP>:8080/`。内网走 HTTP 时保持 `SECURE_COOKIE=false`；若配了 HTTPS 反向代理则设为 `true`。

---

## 配置项

全部通过环境变量注入，都有默认值（见 `src/main/resources/application.yml`）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `8080` | 服务端口 |
| `BIND_ADDRESS` | `127.0.0.1` | 监听地址；局域网访问设为 `0.0.0.0` |
| `DATABASE_URL` | `jdbc:h2:file:./data/qiheng;MODE=PostgreSQL;...` | JDBC 地址 |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `sa` / 空 | 数据库账号 |
| `STORAGE_ROOT` | `./data/files` | 上传母版/原件与生成文件的私有存储目录 |
| `LIBREOFFICE_PATH` | `soffice` | LibreOffice 可执行文件路径（生成 PDF 必需） |
| `SECURE_COOKIE` | `false` | HTTPS 部署时设为 `true` |
| `MAX_UPLOAD_MB` | `30` | 单文件上传上限 |
| `WORKER_ENABLED` / `WORKER_CONCURRENCY` | `true` / `1` | 生成任务后台线程 |
| `CONVERT_TIMEOUT_SECONDS` | `120` | 单次 DOCX→PDF 超时 |
| `RENDER_PROFILE` | `libreoffice-noto-cjk-v1` | 渲染指纹标识（换字体/换 LibreOffice 版本时改） |
| `LOGIN_ACCOUNT_LIMIT` | `10` | 同一账户 15 分钟内允许的登录失败次数 |
| `LOGIN_SOURCE_LIMIT` | `50` | 同一来源 15 分钟内允许的登录失败次数 |
| `TRUST_FORWARDED_HEADER` | `false` | 仅当应用前有可信反向代理时设为 `true` |
| `REVIEW_TTL_MINUTES` | `30` | "全文确认"凭据有效期 |
| `CLEANUP_ENABLED` / `ORPHAN_RETENTION_HOURS` | `true` / `24` | 孤儿文件回收与过期计数清理 |
| `SCAN_COMMAND` | 空 | 可选：接入病毒扫描命令 |

也可以用命令行参数覆盖，例如 `--server.port=8081 --app.storage=/data/files`。

---

## 使用流程

### 管理员 / 合同维护员：准备模板

1. 模板中心 → **新建模板**（编号、名称、分类）→ **创建版本**。
2. **上传母版**：选择 DOCX（可选传一份 PDF 作为参考原件）。母版制作方式见 [docs/模板维护指南.md](docs/模板维护指南.md)。
3. **字段配置**：核对自动生成的字段（中文名、类型、必填、默认值），可点「字段名中文化」批量改中文名，或直接粘贴一份字段配置 JSON。
4. **校验** → **试填** → **查看试填 PDF** → **确认试填** → **发布**。

### 业务人员：起草合同

1. 新建合同 → 选模板 → 填名称，进入填报页。
2. 按控件填写（金额/日期/下拉等有格式校验，必填项缺失会汇总提示），随时**保存草稿**（关闭弹窗也会自动保存）。
3. **生成全文预览** → **查看全文 PDF** 核对 → **确认全文并定稿**。
4. 下载 Word / PDF；线下签字盖章后回来**登记已签署**（上传扫描件归档，可作废重传）。
5. 需要作废：定稿/已签署的合同先**作废**（保留全部历史）；需要重来：**派生新草稿**；模板升级后：**升级到 Vx**（会做字段迁移）。

---

## 业务规则

### 模板与字段

- **占位符协议**：只支持字面量 `{{key}}`，`key` 为 `^[a-z][a-z0-9_]{0,63}$`（小写字母开头，可含数字与下划线）。没有模板语言、没有表达式、不解析跨段占位符。
- **安全限制**（上传即校验，不合格直接拒绝）：不支持宏/嵌入对象/自定义 XML、外链、修订痕迹（`w:ins`/`w:del`）、文本框/内容控件/域里的占位符、**纵向合并单元格**里的占位符（横向合并允许）；占位符跨不同样式（中途换字体/加粗/字号）会被拒绝。
- **占位符替换保留原格式**：值继承占位符所在 run 的字体、下划线等格式，因此"填空下划线"只要给占位符加下划线即可。
- **金额大写自动计算**：字段类型 `computed` + `calculator: rmb_upper`，来源必须是一个金额字段，由后端重算，客户端回传的值会被忽略。
- **日期按中文输出**：日期字段存 ISO（`2025-09-01`），写入正文时按 `format: chinese` 输出为 `2025年9月1日`。
- **字段默认值**：字段配置里的 `default` 会在**新建合同时预填进草稿**（计算字段不参与），可随意改。

### 合同编号

`HT-<年份>-<6 位序号>`（例 `HT-2026-000001`），序号取**当年已用编号里最小的空位**（不使用数据库序列）：

| 现有编号 | 下一个新合同 |
| --- | --- |
| 无（全部删除了） | `HT-2026-000001` |
| `000001` | `HT-2026-000002` |
| `000001`、`000002`、`000007` | `HT-2026-000003`（补空位） |
| `000001`…`000005` | `HT-2026-000006` |
| 只有上一年的编号 | `HT-2026-000001`（按年各自编号） |

分配在写锁内完成，`contract_no` 上有唯一约束兜底；新建/派生/彻底删除都会把编号写进审计。

### 删除规则

| 对象 | 规则 |
| --- | --- |
| 合同 | **物理删除**：连修订、生成任务、预览/确认凭据、签署件、只被它引用的 Word/PDF 一起删除，**不可恢复**（审计保留）。草稿——有权限的用户可删；已作废——仅管理员；已定稿/已签署——必须先作废。 |
| 模板版本 | **物理删除**：仅草稿可删，且无合同引用、无运行中任务。删除后版本号被释放，下个新版本会重新使用它。 |
| 模板 | **物理删除**：连全部版本（含已发布）、试填产物与母版文件一起删除。**唯一硬约束：有合同引用时拒绝删除**，这种情况请改用「停用」。 |
| 版本命名 | 纯元数据，不改 `lock_version`、不参与渲染指纹，**不会**让已完成的试填确认失效。 |

### 状态与不可变性

- 已发布版本**不可原地修改**（含字段配置），但**只改字段显示名**是允许的（「字段名中文化」，不影响生成正文）。
- 合同定稿后，其修订快照与生成文件不可变；再次修改需**派生新草稿**或**作废**后处理。
- 模板"停用"只阻止**新建**合同；在途草稿仍可打开、保存、生成、升级。

---

## 项目结构

```
ContractHub/
├─ src/main/java/cn/qiheng/contracthub/
│  ├─ ContractHubApplication.java   启动类（含命令行参数入口）
│  ├─ LocalAdminController.java     本地管理员初始化/恢复（--init-admin / --recover-admin）
│  ├─ AccountController.java        登录、会话、改密、账号管理、健康检查
│  ├─ SecurityFilter.java           CSRF 与会话校验过滤器
│  ├─ AuthService.java              认证、权限、限速、审计写入
│  ├─ TemplateController.java       模板/版本/字段配置/上传/试填/发布
│  ├─ ContractController.java       合同填报、生成、定稿、签署件、作废、派生、彻底删除
│  ├─ DocumentEngine.java           DOCX 解析/占位符扫描与替换/颜色与下划线识别
│  ├─ FieldRules.java               字段配置校验、填报值校验、人民币大写
│  ├─ GenerationService.java        DOCX→PDF 异步生成与任务队列
│  ├─ FileStore.java                私有文件存储、引用回收、孤儿清理
│  ├─ OperationsController.java     审计日志查询、系统状态
│  ├─ Db.java / ApiException.java / ExceptionAdvice.java / RequestContext.java
├─ src/main/resources/
│  ├─ application.yml               全部配置项（环境变量优先）
│  ├─ db/migration/V1…V5            Flyway 迁移脚本
│  └─ static/                       index.html / app.js / app.css（零构建前端）
├─ scripts/test-auth.cjs            登录与权限的端到端检查（Playwright + H2 内存库）
├─ samples/                         示例模板与示例合同（纯示例数据）
├─ docs/                            使用手册、部署运维、模板维护、接口一览
├─ 一键启动契衡系统.bat / start.bat / 停止契衡系统.bat
└─ pom.xml
```

---

## 接口一览

统一前缀 `/api/v1`。写请求需要先 `GET /auth/csrf` 拿令牌，并在请求头带 `X-CSRF-Token`（会话 Cookie 同源自动携带）。

| 分组 | 主要接口 |
| --- | --- |
| 认证 | `GET /health`、`GET /auth/csrf`、`POST /auth/login`、`GET /auth/me`、`POST /auth/logout`、`POST /auth/change-password` |
| 账号（管理员） | `GET/POST /users`、`PATCH /users/{id}`、`POST /users/{id}/reset-password`、`DELETE /users/{id}` |
| 模板 | `GET/POST /templates`、`GET/PATCH/DELETE /templates/{id}`、`POST /templates/{id}/versions`、`GET /templates/{id}/current-source` |
| 模板版本 | `POST /template-versions/{id}/files`（上传母版）、`GET/PUT /template-versions/{id}/schema`、`POST …/sync-fields`、`POST …/labels`、`POST …/validate`、`POST …/test-render`、`GET …/test-preview`、`POST …/test-review`、`POST …/publish`、`PATCH …/note`、`GET …/source`、`DELETE /template-versions/{id}` |
| 合同 | `GET/POST /contracts`、`GET /contracts/stats`、`GET/PATCH/DELETE /contracts/{id}`、`POST /contracts/{id}/generate`、`POST …/finalize`、`POST …/void`、`POST …/derive`、`POST …/owner`、`GET …/download`、`POST …/upgrade-preview`、`POST …/upgrade` |
| 修订与预览 | `GET /contracts/{id}/revisions/{rid}/preview`、`POST /contracts/{id}/revisions/{rid}/review`、`GET …/download`、`GET /generation-jobs/{id}` |
| 签署件 | `GET/POST /contracts/{id}/signed-attachments`、`GET …/{aid}/download`、`POST …/{aid}/void` |
| 运维 | `GET /audit-logs`、`GET /system/status` |

更完整的字段与错误码说明见 [docs/接口一览.md](docs/接口一览.md)。

---

## 开发与测试

```bash
mvn -o package -DskipTests     # 离线构建（依赖已在本地仓库时）
mvn spring-boot:run            # 开发运行
```

端到端检查（登录、首登强制改密、权限拦截，使用内存库、不碰项目 data 目录）：

```bash
mvn package
node scripts/test-auth.cjs        # 需要 Node.js、Playwright 与 Microsoft Edge
```

接口健壮性回归（不需要浏览器，只要 Node + java：文件不存在返回 404 而不是 500、未就绪状态返回 409 提示、分页越界不再报数据库错误、`SCAN_COMMAND` 支持带参数）：

```bash
mvn package
node scripts/test-api-guards.cjs  # 16 项检查，全部通过才输出 ALL_PASS
```

约定：

- **所有 SQL 必须同时兼容 PostgreSQL 与 H2**（H2 用于自动化测试）。历史上出现过只兼容 H2 的写法（`MERGE ... KEY`、`NEXT VALUE FOR`），在 PostgreSQL 上直接语法错误。
- 前端改动直接改 `src/main/resources/static/`，刷新即生效（无构建步骤）。
- 时间列目前是 `TIMESTAMP`（无时区），按 JVM 本地时区往返；跨时区部署前建议先规划 `timestamptz` 改造。

---

## 部署与运维

- 启动：`一键启动契衡系统.bat`（Docker + PostgreSQL + 应用 + 健康检查）；停止：`停止契衡系统.bat`。
- 日志：`data/application.log`（连不上库、LibreOffice 缺失等都记在这里）。
- 备份：**数据库 + `STORAGE_ROOT` 两样都要**。
- 迁移到另一台电脑：见 [docs/部署与运维手册.md](docs/部署与运维手册.md)（含离线内网准备、数据库快照导入、局域网访问、排错表）。
- 升级：替换 `target/ContractHub-1.0.0.jar` 后重启，Flyway 会自动执行新增迁移。

---

## 安全与数据

- **不要把 `data/` 提交到 Git**：里面是 H2 数据库文件、上传的合同母版与生成的 Word/PDF，属于真实业务数据。`.gitignore` 已默认排除。
- **不要提交数据库快照**（`*.sql`/`*.dump`）、迁移包、以及含真实合同内容的模板样例。仓库里的 `samples/` 只有纯示例数据。
- **首次部署请修改默认数据库密码**：`一键启动契衡系统.bat` / `start.bat` 里的 `POSTGRES_PASSWORD` 与 `DATABASE_PASSWORD` 是一套**仅供本地开发容器使用**的默认口令，正式环境务必替换（改容器 + 脚本，两处保持一致）。
- 默认只监听 `127.0.0.1`；开放到局域网前请确认网络环境可信，并考虑启用 HTTPS（`SECURE_COOKIE=true`）。
- 上传的 DOCX 会被做结构与安全检查（拒绝宏、外链、修订、文本框/域占位符等），但**不做病毒扫描**；内网如需查毒，可配置 `SCAN_COMMAND`。
- 审计日志只记录操作与元数据，不记录密码与完整填报内容。

---

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 打不开网页 / `Java startup failed` | 看 `data/application.log`；常见为 8080 被占用、数据库没起来 |
| `PostgreSQL startup timeout` | Docker Desktop 没启动，或 5432 端口被别的程序占用 |
| 生成合同时报转换失败 | 未安装 LibreOffice，或 `LIBREOFFICE_PATH` 不对 |
| 上传母版报 `COMPLEX_TABLE` | 占位符在纵向合并的单元格里，请移到普通单元格（横向合并可以） |
| 上传母版报 `SPLIT_RUN_STYLE` | 占位符中途换了字体/加粗/字号：全选占位符 → 清除格式 → 重新输入 |
| 发布时报"字段绑定不匹配" | 字段配置与母版不一致：点【按母版同步字段】或重新上传母版（会自动对齐） |
| 发布时报"母版中未找到任何 {{占位符}}" | 母版里既没有 `{{}}`，也没有 ≥3 下划线或标记色文字 |
| 登录被拒 `429` | 触发失败限速（默认账户 10 次/15 分钟）；等待或调高 `LOGIN_ACCOUNT_LIMIT` |
| 合同列表看不到别人建的合同 | 普通用户只看到自己的；管理员可看全部 |
| 中文显示成方块 | 系统缺中文字体（中文版 Windows 自带；Linux 需装 Noto CJK） |
| 编号没有从 001 开始 | 编号按"当年已用编号里的最小空位"分配；删空即回到 `000001` |

---

## 文档

| 文档 | 面向 |
| --- | --- |
| [docs/使用手册.md](docs/使用手册.md) | 业务人员：填报、生成、定稿、签署、作废、派生 |
| [docs/模板维护指南.md](docs/模板维护指南.md) | 管理员/维护员：怎么在 Word 里标占位符、字段配置、报错对照表 |
| [docs/部署与运维手册.md](docs/部署与运维手册.md) | 运维：环境要求、配置、局域网、备份恢复、迁移到新机器、排错 |
| [docs/接口一览.md](docs/接口一览.md) | 开发：接口、权限、错误码 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新记录 |

---

## 许可

内部业务系统，**保留所有权利**（见 [LICENSE](LICENSE)）。如需以开源许可（如 MIT）发布，替换 `LICENSE` 并在本节注明即可。
