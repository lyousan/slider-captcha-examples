## 自动化过滑块示例

技术栈：

`playwright`用于模拟浏览器，`selenium`同理；
`opencv`用于识别滑块位置。

----

目前支持：

| name | code   | url                                         |
| ---- | ------ | ------------------------------------------- |
| 数美   | shumei | https://www.ishumei.com/new/product/tw/code |

----

测试方式：

```http
GET http://localhost:8080/test/{code}
# e.g.
# GET http://localhost:8080/test/shumei
```

----

注意事项：

**IDE中运行时需要将resources目录复制到target目录下**

**打包后将jar包放在resources同级目录**