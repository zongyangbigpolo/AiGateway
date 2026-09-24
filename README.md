# AI Gateway for Android

基于 [2dust/v2rayNG](https://github.com/2dust/v2rayNG) 的 Android 节点管理与自动分流客户端。它不是 AI 模型服务或 API Key 管理平台，而是通过 Android VPN + Xray，为手机应用决定哪些连接走代理、哪些连接通过当前网络直连。

## 功能

- 复用 v2rayNG 的服务器节点管理、订阅更新、节点解析、节点切换与连接状态。
- 客户在主页或订阅页点击「+」后只输入邀请码，将邀请码兑换成独立订阅；服务端维护邀请码与节点组的映射，不要求客户手动填写服务器地址。
- 每次开启网关、重新连接和更新订阅都在线核验邀请码授权；运行中定期复核，到期自动停止，不提供离线宽限或缓存节点绕过。
- 首次启动自动导入内置 AI 过滤规则及 GitHub 上的 Loyalsoldier Geo 数据快照，不需要先联网下载。后续启动不会覆盖用户规则或已替换的数据文件，包括主动清空的路由规则。
- 默认 OpenAI/ChatGPT、Anthropic/Claude、Gemini 等列出的 AI 域名优先走所选代理节点；随后按 GitHub 数据分流国内/海外域名及国内 IP；局域网及未命中流量直连。内置列表是起始配置，不保证覆盖每个 AI 网站的全部登录、支付、静态资源域名。
- 支持从手机文件选择器导入 UTF-8 JSON 域名/IP 规则，先校验、确认，再替换；支持恢复内置规则。
- 规则可编辑、启停、排序、锁定；未命中策略可切换为直连或代理。锁定规则在导入时保留且优先匹配。
- 默认启用核心 DNS 分流、域名嗅探和原版「追加 HTTP 代理至 VPN」选项（Android 10+），使支持的浏览器按明确的目标域名分流；可在设置中关闭，已保存的选择不会被覆盖。正常代理节点的延迟测试仍测试代理，而不是直连出口。
- 独立包名 `io.github.zongyangbigpolo.aigateway`，可与原版 v2rayNG 共存。保留原代码命名空间以减少无关变更。

## 使用

1. 安装 APK，点击主页「+」输入运营方发放的邀请码。运营方需先在后台绑定自己的代理节点；项目本身不提供代理服务器或凭据。
2. 在主页选择节点，点击连接，并授予 Android VPN 权限。
3. 打开侧栏的「路由设置」，查看已启用规则及「未命中规则的流量」。默认规则已自动加载。
4. 在路由设置右上角菜单选择「导入过滤文件（JSON）」，选择文件，确认替换。也可以选择「恢复内置 AI 规则」；恢复只替换规则，不改变未命中策略。
5. 返回主页后，正在运行的连接会自动重连以应用规则，可能短暂中断当前连接；未连接时在下次连接生效。

## 邀请码与映射服务

客户端入口位于主页右上角「+」，订阅页的「+」也进入同一兑换页面，不再进入手动地址添加页面。**客户只输入邀请码，不显示或填写接口地址**。兑换成功后自动添加一个节点订阅组；再选择该组的节点连接。它不会替换过滤 JSON、默认分流或其他订阅。后续在订阅更新入口获取运营方调整后的节点，也可在订阅设置中启用定时更新。

映射关系放在**现有阿里云服务器上的独立 HTTPS 服务 + SQLite**，而不是写进 APK。项目附带 [`invitation-service/`](invitation-service/README.md)：通过服务器本机 CLI 导入节点文件、生成限次/限时邀请码、停用邀请码和独立订阅，没有公开的管理接口。服务地址必须在用户尚未连接代理时可访问。也可迁移到其他 Linux 服务器，不依赖云厂商 SDK。

接口地址只在构建配置中指定，不在客户页面展示，不包含管理员密钥。工程默认使用运营方的阿里云 HTTPS 接口；运营方可在构建时覆盖：

```sh
cd V2rayNG
./gradlew :app:assemblePlaystoreDebug -PINVITATION_SERVICE_URL=https://invite.example.com
```

示例域名不是已部署服务，生产打包必须使用自己部署的接口。发布版只允许 HTTPS，禁止跨站返回订阅和重定向；调试版仅额外允许 `localhost`、`127.0.0.1`、`::1`、模拟器宿主机 `10.0.2.2` 的 HTTP。例如本地联调可传入 `-PINVITATION_SERVICE_URL=http://10.0.2.2:18084`，客户页面仍只有邀请码。邀请码接口契约与阿里云部署方式见服务端 README。

邀请码订阅在订阅列表中只显示「邀请码订阅（自动管理）」，不显示/分享接口 URL，编辑订阅也不会出现地址输入框。普通手动订阅沿用原有界面。隐藏界面不是凭据防提取机制：APP 必须保存节点配置，用户主动导出的完整备份仍可能包含节点和订阅凭据。

同一设备上，同一服务、同一邀请码的重试会复用持久化请求 ID，避免超时重复消耗次数；成功后重复兑换不会新增重复订阅组。清空数据或重装会丢失该请求 ID，已经用完的一次性邀请码需要运营方重新发放。服务返回的节点订阅最大 1 MiB、500 个去重节点，仅接受支持的节点 URI/其 Base64，不接受完整核心 JSON、Clash YAML 或嵌套订阅 URL；全部节点校验成功后才替换原节点，异常响应保留旧配置。

**邀请码同时控制本版本 APP 的持续使用权限。** 每次启动（包括自动重连、系统服务重启）先向内置后台核验，成功后才建立 VPN、代理监听或 Root 转发；手动导入的旧节点不能绕过授权。每次更新订阅先校验再下载；校验失败保留旧节点，但停止使用该订阅的网关。网络不可用、授权到期或被撤销均不允许使用缓存授权继续运行。

运行中每 60 秒在线复核一次，请求超时为 25 秒；撤销或失联会在下一次复核失败时停止，不是服务端即时推送。另有独立的到期检查，按服务器返回的剩余有效时间及 Android 单调时钟停止连接，不因修改手机日期延期；实际执行受 Android 调度影响。最终有效期取「邀请码截止时间」与「兑换后授权截止时间」的较早者，晚兑换不会延长邀请码期限。

**这仍不等于代理服务器账号系统。** 本版本能停止自己的网关，但已下发的共享节点密码在旧版、修改版或其他客户端中仍可能有效。需要全面撤销访问或禁止分享时，必须由代理服务器同步撤销用户凭据。数据库备份和客户端订阅 URL 都应当作为敏感数据保护。

### 当前阿里云后台（运营方操作）

独立服务为 `ai-gateway-invitation.service`，程序位于 `/opt/ai-gateway-invitation/server.py`，数据库位于 `/var/lib/ai-gateway-invitation/service.sqlite`，以 `aigateway-invite` 用户运行，只监听 `127.0.0.1:18084`。已有的 `nebula-web.service` 继续管理 HTTPS 和证书续期，只新增兑换、订阅和授权校验路由；不开放公网 18084，不修改其他业务路由。通用 nginx 配置参考 [`nginx-locations.conf`](invitation-service/deploy/nginx-locations.conf)。

实际客户节点必须由运营方明确绑定；联调节点和邀请码不能发给客户。以 root SSH 登录后，将专用节点 URI 文件安全放在服务用户可读取的私有目录，再执行：

```sh
runuser -u aigateway-invite -- python3 /opt/ai-gateway-invitation/server.py \
  --db /var/lib/ai-gateway-invitation/service.sqlite \
  group customers --name "AI Gateway" --file /var/lib/ai-gateway-invitation/customer-nodes.txt

runuser -u aigateway-invite -- python3 /opt/ai-gateway-invitation/server.py \
  --db /var/lib/ai-gateway-invitation/service.sqlite \
  invite customers --max-uses 1 --expires-days 30 --grant-days 30
```

生成的邀请码只打印一次，私下发送给客户，不放进日志、Git 或公开文档。重新执行 `group` 更新同一节点组后，已发出的订阅会在更新时获取新节点。删除暂存的节点明文文件；数据库和备份按服务端文档保护。服务安装不涉及业务仓库的 Git 操作；今后如操作该主机的业务仓库，应使用仓库属主 `admin`，不能用 root 执行 Git。

## 默认 GitHub 规则与后续更换

默认采用 [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)，不是直接解析原始 GFWList。APK 内置 `202609240010` 快照，包含 `geosite.dat` 和 `geoip.dat`；另附 `Loyalsoldier/geoip` 的 `202609240030` 兼容数据 `geoip-only-cn-private.dat`。来源、上游许可证及归属见 [GEODATA-NOTICE](GEODATA-NOTICE)。

默认路由先执行 AI 及局域网规则，再使用 `geosite:cn` 直连、`geosite:geolocation-!cn` 代理、`geoip:cn` 直连，最后采用可配置的未命中出口。这些都是可编辑/停用/删除的普通路由规则，不是隐藏的强制策略。

在「路由设置 → GitHub 规则源与更新」（也可从侧栏进入资源文件管理）：

1. 默认显示 Loyalsoldier 规则源；可选择其他预设 GitHub 仓库。
2. 编辑文件条目可保存自己的 HTTPS 下载 URL；保存 URL 不删除当前文件，点击下载/更新才替换数据。自定义单文件 URL 优先于仓库预设；再次选择仓库预设并确认，会清除对应自定义地址/仅本地标记，但仍保留当前文件直到新数据下载成功。
3. 从文件菜单可导入本地 `.dat` 文件并确认替换，保持原文件名 `geosite.dat` / `geoip.dat`。本地导入不会被启动初始化覆盖。
4. 下载失败或文件校验失败时保留原文件并显示失败条目。返回主页重新连接后使用更新的数据。

每个数据文件最大 64 MiB。为确保默认路由可用，替换的 `geosite.dat` 必须包含非空 `cn`、`geolocation-!cn` 分类；GeoIP 文件必须包含非空 `cn`、`private` 分类，且为合法 Geo protobuf 数据。仅有名称相同的 JSON/HTML/YAML 文件不能替代 `.dat` 文件。使用其他 Geo 分类的自定义路由仍需自行确保数据源含有对应分类。

构建时对内置快照执行固定 SHA-256 校验；运行时更新检查 HTTPS、大小和数据结构/必需分类，不会将可变的最新文件与旧快照哈希比较。只使用可信的更新源；本地/自定义文件不会自动获得上游真实性背书。

## 过滤文件

文件是 JSON 数组，示例：

```json
[
  {
    "remarks": "公司内网直连",
    "domain": ["domain:corp.example.com"],
    "outboundTag": "direct"
  },
  {
    "remarks": "AI 走代理",
    "domain": ["domain:openai.com", "full:api.anthropic.com"],
    "outboundTag": "proxy"
  },
  {
    "remarks": "局域网直连",
    "ip": ["192.168.0.0/16", "fc00::/7"],
    "outboundTag": "direct"
  },
  {
    "remarks": "阻断示例",
    "domain": ["domain:blocked.example.com"],
    "outboundTag": "block",
    "enabled": true
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `outboundTag` | 必填：`proxy` 所选节点、`direct` 当前网络、`block` 阻断 |
| `domain` | 与 `ip` 二选一，非空字符串数组；`domain:example.com` 匹配本域及子域，`full:api.example.com` 仅精确匹配，`geosite:cn` 引用 GeoSite 分类 |
| `ip` | 与 `domain` 二选一，非空数组，支持 IPv4/IPv6 地址及 CIDR、`geoip:cn` 等 GeoIP 分类 |
| `remarks` | 可选，规则说明 |
| `enabled` | 可选布尔值，默认 `true` |
| `locked` | 可选布尔值，默认 `false`；锁定后后续导入保留此规则 |

规则文件最大 1 MiB、最多 10,000 条规则，支持 UTF-8 BOM。错误类型、非法域名/IP、未知字段均拒绝，不部分导入。`[]` 清除未锁定规则，全部流量按未命中策略处理；不会在重启后被自动重新填充。域名不支持 URL、通配符或裸域名；国际化域名请使用 Punycode。

内置文件：[ai_gateway_rules.json](V2rayNG/app/src/main/assets/ai_gateway_rules.json)。修改此文件会影响新安装及主动恢复默认规则，不覆盖现有安装。

此文件入口是明确限定的域名/IP JSON 格式，**不是任意过滤列表转换器**。Clash YAML、Adblock/GFWList、正则、二进制 Geo 数据文件不适用；`.dat` 数据请使用「GitHub 规则源与更新」入口。原版预设使用原版的代理默认出口，其中具体规则仍可直连；导入高级剪贴板规则不改变当前未命中策略。高级规则导出的 JSON 可能超出文件导入支持的字段，应使用原有剪贴板导入入口。

## 分流边界

- 按 Xray 路由顺序评估已启用规则，首条匹配生效；默认 `IPIfNonMatch` 先匹配域名，域名阶段没有匹配时解析 IP 再评估 IP 规则。无匹配时由默认出口处理，不通过插入兜底规则阻断 IP 二次匹配。
- 默认关闭 VPN 层面的局域网绕过，由规则决定局域网是否直连。若手动启用系统层面的 LAN 绕过、分应用排除，则这些流量不会进入核心规则。
- 默认本地 DNS 使用「直连 DNS」（上游默认 `223.5.5.5`），代理域名使用「远程 DNS」（上游默认 `1.1.1.1`）经代理解析；可在设置中替换。匹配到 DNS 规则后不回退到未匹配的解析器。DNS 地址不是从 Wi-Fi 自动发现的。
- 支持 VPN HTTP 代理的浏览器会将目标域名交给核心，仍按过滤规则选择直连或代理，不是全局代理。其他应用的域名识别依赖核心 DNS/嗅探能力；应用忽略系统代理、自带 DoH、ECH、不可识别的加密握手或直接使用 IP 时，域名规则可能无法命中，可补充 IP 规则或将未命中策略设为代理。不能承诺所有应用自动识别。
- 「自定义完整核心配置」沿用上游行为，使用自己的路由、DNS、出站；不应用本项目普通节点的自动分流设置。
- 代理匹配连接失败不会自动降级到直连。VPN 未启动或被系统终止时，Android 可恢复普通网络；需要系统级防漏时启用 Android 的始终开启 VPN 和阻止未通过 VPN 的连接，并自行确认设备支持情况。
- 这是网络分流，不读取 AI 提示词、不做 TLS 中间人解密，也不提供本地模型。

## 构建

Android 工程位于 `V2rayNG/`，使用工程内 Gradle Wrapper；需要 JDK 21、Android SDK Platform `37.0`、Build Tools `37.0.0`、NDK `29.0.14206865`、Git、curl 和 shasum。将 `JAVA_HOME` 设置为 JDK 21，`ANDROID_HOME` 设置为 SDK 路径。macOS 的 Android Studio 可使用 `/Applications/Android Studio.app/Contents/jbr/Contents/Home` 和 `$HOME/Library/Android/sdk`。

[`scripts/prepare-native.sh`](scripts/prepare-native.sh) 校验并下载 Xray AAR，同时从固定提交构建四种 ABI 的 hev 库；固定版本和 SHA-256 在 [`scripts/native-dependencies.env`](scripts/native-dependencies.env)。只运行 JVM 测试时可使用 `--aar-only`。APK 构建会拒绝缺少原生库的配置，不使用空 AAR 或 JNI 占位文件。

[`scripts/prepare-geodata.sh`](scripts/prepare-geodata.sh) 准备并校验内置规则快照及许可证，固定版本在 [`scripts/geodata-dependencies.env`](scripts/geodata-dependencies.env)。二进制文件保存在忽略的 `.native-build/geodata/`，通过 Gradle assets 打包，不将大数据文件提交进 Git。缺少或损坏快照时构建会失败，而不是静默生成无法分流的 APK。

```sh
# 先设置本机 JAVA_HOME / ANDROID_HOME，并安装上述 SDK 组件
bash scripts/prepare-native.sh
bash scripts/prepare-geodata.sh
cd V2rayNG
./gradlew :app:testPlaystoreDebugUnitTest \
  --tests 'com.v2ray.ang.util.GatewayRuleFileTest' \
  --tests 'com.v2ray.ang.util.Invitation*Test' \
  --tests 'com.v2ray.ang.core.Invitation*Test' \
  --tests 'com.v2ray.ang.core.GatewayRoutingTest' \
  --tests 'com.v2ray.ang.core.ServiceLifecycleTest' \
  --tests 'com.v2ray.ang.handler.GatewaySettingsTest' \
  --tests 'com.v2ray.ang.util.GatewayGeoFilesTest' \
  --tests 'com.v2ray.ang.viewmodel.UserAssetViewModelTest' \
  --tests 'com.v2ray.ang.HttpUtilTest'
./gradlew :app:assemblePlaystoreDebug
```

APK 位于 `V2rayNG/app/build/outputs/apk/playstore/debug/`。GitHub Actions 构建用于下载调试 APK，不代表已签名发布版。发布前需自有签名密钥、真机 VPN 验证，并按 GPL 提供对应源代码和构建说明。

### 从 GitHub 下载安装包

打开 [Actions → Build AiGateway](https://github.com/zongyangbigpolo/AiGateway/actions/workflows/build.yml)，选择成功的运行，在 **Artifacts** 下载 `AiGateway-版本-debug-apks-…`，解压后安装 `AiGateway-版本-debug-universal.apk`。同时提供四种 ABI 专用包和 `SHA256SUMS`；APK 保存 30 天，测试报告保存 14 天。

每次分支推送、PR 和 `v*` 标签推送都会构建；默认分支包含工作流后也支持 **Run workflow** 手动触发，接口地址仅由运营方在构建参数中指定。推送与 APP 版本一致的标签（当前 `v0.1.0`）会将通用调试 APK 发布到 GitHub 预发布版本，方便长期下载；不会覆盖已发布版本。

**向客户分发之前应配置固定签名。** 仓库 Actions secret `AI_GATEWAY_DEBUG_KEYSTORE_BASE64` 可保存专用于本项目的标准 Android debug keystore 的 Base64（别名 `androiddebugkey`，store/key 密码均为 `android`）。不要上传个人全局开发密钥，不要将任何密钥提交进 Git；妥善离线备份项目密钥。只有受信任的 push/手动构建使用该 secret，PR 构建不会使用。未配置时每个 CI 环境生成临时 debug 签名，安装更新可能需要卸载，导致邀请码凭据丢失，已消费的一次性邀请码不能重新兑换。正式发布请另行配置生产签名。

CI 运行网关分流、默认数据安装、规则源更新和连接生命周期的针对性回归测试。重连会等待旧核心、VPN 资源及 Android 服务完整退出，不依赖固定延时；退出失败时不会继续启动新连接，并显示错误。上游完整测试套件仍可通过 `./gradlew testPlaystoreDebugUnitTest` 单独运行；当前继承版本的 `UtilsTest.test_isIpAddress`、`UtilsTest.test_IsIpInCidr` 存在断言/MMKV 初始化问题，不在本次网关修改中修复。

真机验收应覆盖：首次安装默认规则、节点连接、匹配 AI 请求的代理出口、普通请求的直连出口、IPv4/IPv6、导入失败保留规则、清空后重启、修改后重连、锁定规则优先级、代理不可用时不降级直连。需要可用节点和受控目标服务器来核对真实出口。

## 来源与许可证

派生自 v2rayNG **2.2.6**，固定上游提交 [`15b4fff8e45da9bc0acaa5cc1d80a1d3531e8712`](https://github.com/2dust/v2rayNG/commit/15b4fff8e45da9bc0acaa5cc1d80a1d3531e8712)。
2026-09-24 的 AI Gateway 修改包括应用标识、默认 AI 规则、规则文件导入、默认出口及 DNS 分流、回归测试和构建工作流。

继续采用 [GNU GPL v3](LICENSE)，保留上游版权和许可声明；[上游 README](README.upstream.md) 为来源记录。第三方组件按各自许可证分发。本项目不是 2dust 官方发行版。
