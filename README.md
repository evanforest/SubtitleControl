# SubtitleControl
字幕文件播放控制工具


实现对字幕文件（比如srt文件）的播放流程控制，根据每个字幕的起始与结束时间精确播放字幕，并提供了暂停、恢复、休眠、跳转等操作。

代码中只提供了对srt文件的解析类，可以根据需要自行扩展对其它字幕文件的解析。

[![Release](https://jitpack.io/v/evanforest/SubtitleControl.svg)](https://jitpack.io/#evanforest/SubtitleControl)

## 开始
1.在根目录build.gradle中添加仓库
```
allprojects {
	repositories {
		maven { url "https://jitpack.io" }
	}
}
```

2.添加依赖
```
dependencies {
	implementation 'com.github.evanforest:SubtitleControl:1.0.0'
}
```


