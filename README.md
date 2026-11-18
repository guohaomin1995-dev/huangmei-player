# 黄梅戏播放器

给奶奶用的平板播放器：开机就是一屏大海报，点一下就全屏播放，看完自动放下一部，下次接着上次的地方放。目标设备是红米 K Pad（8.8 寸，HyperOS），其他 Android 8+ 的手机或平板也能装来测试。

## 给其他家庭：让老人不用按任何按钮就能视频

如果家里老人不会接视频电话，可以参考这个项目：

- **平时**：平板锁定在一个大字、大按钮的戏曲播放器里，老人怎么点都退不出去，不会误入别的应用。
- **家人打微信视频过来**：平板自己亮屏，响 5 秒后自动接通，显示画面。可以设置成只接指定的家人。
- **挂断后**：自动回到播放器，原来在放的戏接着放。
- **重启或断电后**：自动回到播放器，不需要人操作。

需要准备：

- 一台安卓平板（实测：红米 K Pad，HyperOS 4，微信 8.0.80）
- 一台装了 adb 的电脑，用于第一次设置
- 给老人单独注册一个微信号，登录在平板上，和家人互加好友

大致步骤：编译安装 → 设为设备所有者，完全锁定 →（见下文"装到 K Pad 上并完全锁定"）→ 打开微信自动接听（见下文"微信视频自动接听"）。

请先了解这几点：

- **征得老人同意**：自动接听意味着家人随时能看到家里的画面。
- **依赖微信界面**：微信对辅助服务隐藏了界面内容，所以程序是靠截屏找到绿色的"接听"按钮来点的。微信改版后可能失效，需要重新适配。
- **不是官方功能**：这是用辅助服务模拟点击，风险请自行判断。
- **设备所有者要求平板上没有任何账号**：请在设为设备所有者之后，再登录微信。
- 视频文件不在本仓库里，请自备。

## 能放什么格式

播放内核是 libVLC（VLC 3.7），RMVB（RealVideo + Cook）、FLV、MP4、MKV、AVI 等都能直接放，不用转码。
APK 只打包 arm64-v8a，约 72MB。

## 视频放哪、怎么命名

- 目录：`内部存储/Download/黄梅戏/` 或 `内部存储/Movies/黄梅戏/`（两个都会扫描，可以有子文件夹）
- 文件名两种写法都认：
  - `黄梅戏《天仙配》严凤英.rmvb` → 戏名"天仙配"，副标题"严凤英"；`（上部）/（下部）` 会并进戏名显示成"天仙配（上）"
  - `01-天仙配-严凤英.mp4` → 开头序号决定排序（不显示），第一段是戏名，后面是副标题
- 排序：带序号的排最前，其余按戏名拼音
- 海报：和视频同名的 `.jpg`、`.png` 或 `.webp`。没有的话，App 会自动从视频里截一帧
- 原片格式比较杂、音量忽大忽小的话，先用脚本统一处理（需要装 ffmpeg）：

```bash
tools/prepare_videos.sh ~/Downloads/黄梅戏原片 ~/Downloads/黄梅戏
```

## 编译

```bash
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 在手机上试用（不锁定）

1. 手机打开 开发者选项 → USB 调试，以及 USB 安装（小米要登录小米账号才能打开）。
2. 安装 APK。必须带 `-t` 参数，因为 App 标记成了 testOnly：

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

3. 授予文件权限。也可以打开 App 后点"去允许"：

```bash
adb shell appops set --uid app.huangmei.player MANAGE_EXTERNAL_STORAGE allow
```

4. 放几个测试视频：

```bash
adb push 01-天仙配.mp4 01-天仙配.jpg "/sdcard/Movies/黄梅戏/"
```

5. 想试锁定效果：首页**按住右下角的页码 4 秒**打开维护面板，点"固定屏幕（测试）"。这一步用的是系统的屏幕固定，随时可以退出。

## 调试时保持静音

调试时任何设备都不能发出声音。如果要测和声音有关的功能，最多只能开到最低音量。每次在设备上测之前按顺序做下面几步：

1. 把媒体音量设为 0：

```bash
adb shell cmd media_session volume --stream 3 --set 0
```

2. **在放视频之前**打开静音测试开关。开启后播放器音量为 0，也不会自动把音量调高到 40%。屏幕顶部会显示红字"静音测试中"。最简单的办法是在维护面板里打开；用 adb 的话见下面的"用 adb 改设置"。

### 用 adb 改设置

设置存在 `shared_prefs/huangmei.xml` 里。改的时候要注意两点：

- **改完必须立刻杀掉进程**。App 是设备所有者，又有辅助服务一直连着，`am force-stop` 停不掉它。进程活着时，内存里的旧设置会把你改的内容覆盖回去。杀掉后系统会马上把它重新拉起来，重新读取设置。
- **删设置只删那一个元素，不要整行删**。用 sed 加进去的设置和 `</map>` 在同一行，整行删会把 `</map>` 一起删掉，文件就坏了。文件一坏，App 读不出来，会把**所有设置**（常看、进度、锁定开关）当成空的重写一遍。改之前最好先备份：

```bash
adb shell run-as app.huangmei.player cp shared_prefs/huangmei.xml files/prefs_backup.xml
```

（不要把备份命名为 `huangmei.xml.bak`，系统会把同名的 .bak 文件当成正式设置文件恢复回去。）

加一个设置，以打开静音测试为例：

```bash
adb shell "run-as app.huangmei.player sh -c 'sed -i \"s#</map>#<boolean name=\\\"silentTest\\\" value=\\\"true\\\" /></map>#\" shared_prefs/huangmei.xml && kill -9 \$(pidof app.huangmei.player)'"
```

删一个布尔设置，以删掉静音测试为例：

```bash
adb shell "run-as app.huangmei.player sh -c 'sed -i \"s#<boolean name=\\\"silentTest\\\"[^>]*/>##\" shared_prefs/huangmei.xml && kill -9 \$(pidof app.huangmei.player)'"
```

（如果是刚装上、还没打开过 App，设置文件还不存在，上面的命令改不了。这时先打开 App，进维护面板操作。）

3. 测试视频用 `tools/make_test_video.swift` 生成。它只生成画面，没有音轨：

```bash
swiftc -O tools/make_test_video.swift -o /tmp/mkvid
```

```bash
/tmp/mkvid 03-打猪草.mp4 打猪草 "" 20 03-打猪草.jpg
```

### 静音验证声音通路

静音测试模式下，VLC 默认直接不解码音轨。要确认音轨能解码、能送到系统音频输出，又不发出声音，可以临时打开调试开关 `silentDecodeAudio`。打开后照样解码音轨，但 VLC 音量仍是 0，系统媒体音量也保持 0。

1. 写入开关（App 会被重启）：

```bash
adb shell "run-as app.huangmei.player sh -c 'sed -i \"s#</map>#<boolean name=\\\"silentDecodeAudio\\\" value=\\\"true\\\" /></map>#\" shared_prefs/huangmei.xml && kill -9 \$(pidof app.huangmei.player)'"
```

2. 在 App 里放一部戏，然后看两处证据：
   - **VLC 解码统计**：每 5 秒记一次，`audio decoded / played` 在涨、`lost` 为 0 就说明声音正常解码并送出了。

```bash
adb logcat -s Huangmei
```

   - **系统音频记录**：能看到 App 的音频输出，并且标着 `muted ... streamVolume clientVolume`（三层都静音）。

```bash
adb shell dumpsys audio | grep -A3 app.huangmei
```

3. 测完一定要删掉开关：

```bash
adb shell "run-as app.huangmei.player sh -c 'sed -i \"s#<boolean name=\\\"silentDecodeAudio\\\"[^>]*/>##\" shared_prefs/huangmei.xml && kill -9 \$(pidof app.huangmei.player)'"
```

**交给奶奶前**，记得在维护面板里关掉"静音测试"。

## 装到 K Pad 上并完全锁定

设备所有者要求平板上**没有任何账号**（小米账号、Google 账号都不行），所以按这个顺序操作：

1. 登录小米账号，打开 USB 调试和 USB 安装。
2. 安装 APK 并授予文件权限，命令同上。视频放到 `Download/黄梅戏/`。
3. 移除平板上**所有账号**：退出小米账号，并在 设置 → 账号与同步 里删掉 Google 等账号。
4. **关掉锁屏密码**（设置 → 指纹、面部与密码）。有 PIN 的话，重启后会卡在输入密码界面。
5. 确认已经没有账号、也没有锁屏密码：

```bash
adb shell dumpsys account | grep "Accounts:"
```

```bash
adb shell dumpsys lock_settings | grep CredentialType
```

   第一条应显示 `Accounts: 0`，第二条应显示 `CredentialType: NONE`。

6. 设为设备所有者：

```bash
adb shell dpm set-device-owner app.huangmei.player/.AdminReceiver
```

7. 打开 App，它会自动锁定（2026-11-17 已在 K Pad 上验证）。之后每次开机都会自动锁回：
   - 本应用成为系统桌面，开机直接进海报墙，没有锁屏
   - HOME、多任务、返回、下拉通知栏、上滑手势都出不去，只保留长按电源键的关机菜单
   - 插着电时屏幕常亮；亮度默认自动
   - 不管从哪里打开，都只保留一个界面实例（避免两个播放器）

### 维护

- **维护面板**：首页按住右下角的页码 4 秒打开。在面板里可以：
  - 退出锁定、开启锁定、打开系统设置
  - 重新扫描视频，开关"打开时自动续播"和"静音测试"
  - 调亮度：默认自动（跟着屋里光线变）；按"暗一点 / 亮一点"会切到手动，每次调 10%
  - 挑选常看：点"挑选常看"后，海报墙顶部出现红条。这时点海报是加入或取消常看，海报右上角会标"常看 1、2、3…"，代表在第一页的先后顺序。挑完点"完成"
- **"退出锁定"只管到下次重启**：维护完忘了点"开启锁定"也没关系，平板一重启就会自动锁回黄梅戏，系统锁屏也会自动解除。2026-11-18 在 K Pad 上验证过：先退出锁定再重启，开机后直接进入黄梅戏并接着放。每次开机只自动锁回一次，开机后家人再退出锁定，不会被抢回去。小米默认会拦本应用的开机自启，要放开：

```bash
adb shell appops set app.huangmei.player 10008 allow
```

- **装新版本前必须先退出锁定**：锁定状态下小米的安装确认框弹不出来，`adb install` 会报 `INSTALL_FAILED_USER_RESTRICTED`。流程是：
  1. 维护面板点"退出锁定"
  2. 用 `adb install -r -t` 装新版
  3. 打开 App，在维护面板点"开启锁定"。装完后辅助服务重新连上时，如果这次开机还没自动锁回过，也会自动锁回
- **解除设备所有者**（卸载前用）：在维护面板里点两次"解除设备所有者"，或者用 adb：

```bash
adb shell dpm remove-active-admin app.huangmei.player/.AdminReceiver
```

## 微信视频自动接听

家人打微信视频过来，平板响 5 秒后自动接通、显示画面；挂断后回到黄梅戏，原来在放就接着放。平时仍然锁定在黄梅戏。2026-11-18 在 K Pad（HyperOS 4，微信 8.0.80）上实测通过：

- 亮屏放戏时来电：约 10 秒接通，挂断 1 秒后回到原处接着放。
- 灭屏时来电：屏幕自己亮起，约 6 秒接通。挂断后回到黄梅戏的暂停画面，因为灭屏时戏本来就停着，所以不会自动接着放。

### 怎么做到的

- 锁定时把微信也加进锁定白名单，来电界面才弹得出来。微信只许在来电或通话时停在前台，其他时候（挂断后剩下微信首页、奶奶误点进去）自动按"返回"退回黄梅戏。锁定模式下系统不让直接把桌面任务提到前面，返回键可以用。
- 微信对辅助服务**隐藏了界面内容**，读不到"接听"按钮，所以：
  - 来电状态和来电人从微信的来电通知里读，通知正文是"某某邀请你视频通话"。
  - 点接听的方法：辅助服务截一张屏，在下半屏找实心绿色圆按钮，点它的中心。找不到符合大小和形状的绿圆就不点。
  - 注意：微信一打开来电界面就会撤掉来电通知，所以通知撤掉不代表对方挂断了。
- 来电界面被锁定挡住、没有弹出来时，由本应用替微信打开来电通知。
- 接通和挂断按系统音频模式判断：进入通话模式就是接通了，回到普通模式就是挂断了。

### 安装后的一次性设置（adb）

装好新版本之后执行一次。重装本应用不用重做；**重装微信后，第 3、4 步要重做**。

1. 打开辅助服务和通知监听：

```bash
adb shell settings put secure enabled_accessibility_services app.huangmei.player/app.huangmei.player.CallAnswerService
```

   如果平板上还开着别的辅助服务（比如向日葵），要把它们一起写上，用冒号隔开。可以先用 `adb shell settings get secure enabled_accessibility_services` 查看原来的值。

```bash
adb shell settings put secure accessibility_enabled 1
```

```bash
adb shell cmd notification allow_listener app.huangmei.player/app.huangmei.player.CallNotificationListener
```

2. 允许 App 自己重新打开辅助服务。小米系统清后台或重启后，可能把辅助服务关掉或标成"崩溃"，有了这个权限 App 能自动恢复：

```bash
adb shell pm grant app.huangmei.player android.permission.WRITE_SECURE_SETTINGS
```

3. 提前给微信摄像头、麦克风和通知权限，免得第一次来电时弹授权框：

```bash
for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do adb shell pm grant com.tencent.mm android.permission.$p; done
```

4. 打开小米的"后台弹出界面"、"锁屏显示"、"自启动"、"悬浮窗"权限，并让微信和本应用不受省电限制：

```bash
for op in 10021 10020 10008 SYSTEM_ALERT_WINDOW; do adb shell appops set com.tencent.mm $op allow; done
```

```bash
adb shell dumpsys deviceidle whitelist +com.tencent.mm +app.huangmei.player
```

5. 在维护面板里先"退出锁定"，再"开启锁定"，让锁定白名单把微信加进去。面板的"微信自动接听"一行应该显示"开"，后面不应带"辅助服务没打开"。

### 设置项

- 维护面板里有"微信自动接听：开/关"。
- 只接指定的人：写入来电界面上显示的名字（有备注显示备注，没有显示昵称），多个名字用换行分隔。默认不限，微信好友打来都接，陌生人本来就打不进来。

```bash
adb shell "run-as app.huangmei.player sh -c 'sed -i \"s#</map>#<string name=\\\"answerOnly\\\">小明</string></map>#\" shared_prefs/huangmei.xml && kill -9 \$(pidof app.huangmei.player)'"
```

- 其他只能用 adb 写入的设置（写法见"用 adb 改设置"）：
  - `answerDelaySec`：响几秒后接，整数，默认 5
  - `answerVoice`：语音来电也自动接，布尔值，默认只接视频

### 调试

```bash
adb logcat -s CallAnswer
```

关键日志依次是：`来电：某某（视频）` → `第 1 次点接听` → `已接通` → `通话结束` → `通话结束回来，接着放`。

测试时：手机拨出后关掉麦克风，平板的通话音量调到最低（通话音量最低是 1，没法调到 0）。

### 还没测过的情况

- 平板竖着放（找绿色按钮的方法不受方向影响，但没实测）

## 功能

- **常看**：家人挑的戏固定排在第一页（最多 12 部，按挑选的先后顺序，位置不变），页码处显示"常看"；后面的页是其余的戏，按戏名拼音排，常看的不重复出现。播放、自动下一部也按这个顺序。
- **回第一页**：左边"上一页"下面有"回第一页"大按钮，翻到后面的页也能一下回到常看。
- **海报墙**：一页 3×2 张海报，左右两边是翻页大按钮。上次看的那部有金框和"上次在看"标签，看过的显示进度条。
- **播放**：底部一排常驻大按钮（回海报 / 上一部 / 暂停 / 下一部 / 小声 / 大声），点画面不会误操作。暂停后画面中间出现"继续播放"大按钮。
- **断点续播**：每部戏记住看到哪，接着放时往回退 5 秒。看了不到 30 秒，或者只剩最后 1 分钟，就从头放。
- **自动续播**：打开 App 时倒数 8 秒，自动接着放上次那部；也可以点"我自己选"。这个功能能在维护面板里关掉。
- **音量保护**：开始播放时，如果音量低于 40% 就自动调到 40%。"小声"按钮按到底也会留一点声音。
- **遥控器**：蓝牙遥控器或键盘的播放/暂停、上一首/下一首、方向键都能用。
- 放不了的视频会自动跳到下一部。

## 代码结构

| 文件 | 作用 |
|---|---|
| `VideoLibrary.kt` | 扫描目录、解析文件名、自然排序、找海报 |
| `Paging.kt` | 海报墙分页：常看在前，其余在后 |
| `PosterLoader.kt` | 读海报图片或从视频截帧，内存和磁盘缓存 |
| `AppViewModel.kt` | 播放器（libVLC）、播放列表、断点续播、自动下一部、音量、遥控器按键（系统 MediaSession） |
| `Kiosk.kt` | 设备所有者策略、锁定模式、设为桌面；锁定白名单加微信、预先授权微信 |
| `CallAnswer.kt` | 微信视频来电自动接听：来电通知、截屏点接听、挂断切回、辅助服务自愈 |
| `ui/` | 海报墙、播放界面、维护面板 |
