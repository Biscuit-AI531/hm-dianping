# 黑马点评 Java 服务

基于 Spring Boot 的本地生活业务服务，提供店铺、探店笔记、优惠券、用户、关注与秒杀订单等能力。当前仓库中的 [CityLens](../../CityLens/README.md) 通过只读 HTTP 接口使用店铺、商圈、笔记和优惠券数据。

## 技术栈与目录

- Java 8、Spring Boot 2.3.12、MyBatis-Plus 3.4.3、MySQL、Redis、Redisson。
- `src/main/java/com/hmdp/controller`：HTTP 接口；`service/impl`：业务逻辑；`mapper`：数据库访问；`entity` 与 `dto`：数据模型。
- `src/main/resources/db/hmdp.sql`：原始建表和演示数据；`application.yaml`：默认开发配置；`seckill.lua`：秒杀校验脚本。
- 点评原始静态页面位于同级目录 `../nginx-1.18.0/html/hmdp`，不包含在本 Maven 模块中。

店铺详情使用 Redis 缓存；按类别的附近店铺查询使用 Redis GEO。关注动态使用 Redis 有序集合，签到使用位图；秒杀订单流程使用 Lua、Redis Stream 和数据库约束。CityLens 使用的 `/shop/search` 是另一个只读组合搜索接口，支持类别、价格、评分、商圈、关键词、优惠券和附近距离筛选。

## 快速启动：仓库内隔离环境

在仓库根目录 `黑马点评` 执行：

```bash
docker compose -f CityLens/dev/docker-compose.yml up -d
docker compose -f CityLens/dev/docker-compose.yml logs -f java
```

看到 `Started HmDianPingApplication` 后，服务地址为：

| 服务 | 地址 |
| --- | --- |
| Java API | `http://127.0.0.1:8081` |
| 点评原始页面 | `http://127.0.0.1:8080` |
| MySQL | `127.0.0.1:3306` |
| Redis | `127.0.0.1:6380` |

Compose 使用独立的 MySQL、Redis 卷，首次创建 MySQL 卷时导入 `hmdp.sql`，随后导入 `CityLens/dev/02-citylens-evidence.sql`。第二份脚本修正原始笔记与店铺的错配，并加入明确标注为**合成演示数据**的笔记；这些笔记不代表真实用户评价。已有数据库卷不会重新执行初始化脚本，需要同步演示笔记时运行：

```bash
docker compose -f CityLens/dev/docker-compose.yml exec -T mysql \
  mysql --default-character-set=utf8mb4 -u root -pcitylens_local_only hmdp \
  < CityLens/dev/02-citylens-evidence.sql
```

停止服务：

```bash
docker compose -f CityLens/dev/docker-compose.yml down
```

`down` 不删除数据卷；只有明确指定删除卷时数据才会清空。

## 独立启动

准备 JDK 8、Maven 3、MySQL 和 Redis，将 `src/main/resources/db/hmdp.sql` 导入名为 `hmdp` 的数据库。`application.yaml` 包含原课程环境使用的数据库和 Redis 地址，独立运行时需要按自己的环境覆盖。Spring Boot 支持通过环境变量覆盖，例如：

```bash
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&characterEncoding=utf8'
export SPRING_DATASOURCE_USERNAME='root'
export SPRING_DATASOURCE_PASSWORD='<本地数据库密码>'
export SPRING_REDIS_HOST='127.0.0.1'
export SPRING_REDIS_PORT='6379'
export SPRING_REDIS_PASSWORD='' # Redis 无密码时留空；若启用认证则改为实际密码
mvn spring-boot:run
```

Java API 默认监听 `8081`。如需同时打开点评页面，可使用上面的 Compose `web` 服务；单独启动 Maven 不会启动 Nginx。

## 主要接口

接口统一返回 `success`、`data`、`errorMsg`，分页接口还返回 `total`。下表列出 CityLens 实际接入的只读接口：

| 接口 | 用途 |
| --- | --- |
| `GET /shop-type/list` | 店铺分类 |
| `GET /shop/areas` | 已登记的商圈 |
| `GET /shop/{id}` | 店铺详情 |
| `GET /shop/search` | 组合搜索与分页 |
| `GET /voucher/list/{shopId}` | 店铺当前上架券信息 |
| `GET /blog/of/shop?shopId={id}&current=1` | 店铺最近笔记，每页 5 条 |
| `GET /blog/search?shopId={id}&keyword={词}&current=1` | 在指定店铺的笔记标题和正文中检索，每页 5 条 |

`/shop/search` 必须传 `typeId`，可选 `keyword`、`area`、`maxPrice`（元）、`minScore`（评分乘 10，例如 4.5 分传 `45`）、`hasVoucher`、`x/y`、`page`、`size` 和 `sort`。`size` 范围为 1～20；`sort` 支持 `score`、`price`、`distance`。按距离排序必须同时传经纬度 `x/y`；附近搜索限定约 5 公里，并在结果中返回 `distance`（米）。笔记检索的 `keyword` 长度为 2～40 个字符。

示例：

```bash
curl 'http://127.0.0.1:8081/shop/search?typeId=1&maxPrice=120&minScore=45&page=1&size=5'
curl 'http://127.0.0.1:8081/blog/search?shopId=4&keyword=%E7%BA%A6%E4%BC%9A'
```

项目还提供登录、签到、关注、发布与点赞笔记、创建优惠券和秒杀下单等接口。`POST /voucher-order/seckill/{id}` 等接口会改变业务状态并依赖登录用户；CityLens 当前**不调用任何写接口**。`/blog-comments` 尚无业务方法，`/user/logout` 当前返回“功能未完成”，不应作为已完成能力展示。

## 构建与验证

在本目录运行：

```bash
mvn -DskipTests package
```

这能验证 Java 代码编译和打包。现有 `src/test` 中包含连接 MySQL、Redis 的 Spring 集成测试及写入 Redis 的演示测试；运行完整 `mvn test` 前应准备隔离的测试服务，不能将其视为无需外部依赖的单元测试。CityLens 对 Java 只读接口的联调命令见 [CityLens README](../../CityLens/README.md#验证)。
