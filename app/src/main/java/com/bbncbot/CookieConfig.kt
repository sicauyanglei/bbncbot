package com.bbncbot

/**
 * 淘宝登录 Cookie 配置
 *
 * 从 PC 浏览器登录后获取，注入到 WebView 中绕过登录。
 * Cookie 可能会过期，需要定期更新。
 */
object CookieConfig {

    /**
     * 淘芭农场相关 Cookie（domain=.taobao.com）
     *
     * 关键登录 Cookie：
     * - t: 登录态 token
     * - lgc: 登录用户名
     * - _tb_token_: 防 CSRF token
     * - _m_h5_tk / _m_h5_tk_enc: H5 API token
     * - dnk / tracknick / _nk_: 用户昵称
     */
    val COOKIES: Map<String, String> = mapOf(
        "xlly_s" to "1",
        "arms_uid" to "d9a8dcb9-8354-4527-9102-fee8b4cd9216",
        "thw" to "cn",
        "t" to "48621c884e4a74a2713a403d2def5d35",
        "_tb_token_" to "7773131b1ef37",
        "cna" to "4Im/In/1dHQCAXFuk83o5hJO",
        "sca" to "0fc51123",
        "3PcFlag" to "1782094891158",
        "uc1" to "cookie16=VFC%2FuZ9az08KUQ56dCrZDlbNdA%3D%3D&cookie21=U%2BGCWk%2F7p4mBoUyS4E9C&cookie14=UoYWPyQAIZ78lw%3D%3D&existShop=false&pas=0&cookie15=VFC%2FuZ9ayeYq2g%3D%3D",
        "csg" to "7125f129",
        "lgc" to "tb62288171",
        "cancelledSubSites" to "empty",
        "dnk" to "tb62288171",
        "existShop" to "MTc4MjA5OTQwNQ%3D%3D",
        "tracknick" to "tb62288171",
        "_cc_" to "W5iHLLyFfA%3D%3D",
        "_l_g_" to "Ug%3D%3D",
        "sg" to "143",
        "_nk_" to "tb62288171",
        "aui" to "3789464854",
        "mtop_partitioned_detect" to "1",
        "_m_h5_tk" to "0a702ddd7b0e66e632b83ef2ced816ae_1782109586826",
        "_m_h5_tk_enc" to "be84ad314d70664b1ea95e1689b57965",
        "isg" to "BEZGLcspUGk9XQSN-z-wacQIlzzIp4phGDqXTzBvMmlEM-ZNmDfacSzRCW7_gIJ5",
        "tfstk" to "hL3wkligXaaXCHatc9GvhWxBPPhTUAbflrw6xvynwO6si-aq3xk4CovT5qkEMb7shcOYmrkIUoDbhfO4WrUclXaQNRhn9SxHmjvo6fUA9L9WPUtVtIaSji2mnyq3NSz0nxbMTJV4wiXinrAEtJe3nr4mnXAUGJ20orDDTXbAEZopnVtw4XSb9W3UsKwosi_co2PNF8cw2ZvsaK_73J3kdHlLLcD_9x8yLzm-LqruuFW8_Dcz-RakYEWRoWk5-_0SyxMtxoQ9S2OZZYMol_9mlUna5YkOCKtwIAliw4IpUnO-TjDaA6trocet_c3RsnoesRct62b9kcJtAPixQWPH2-N2aXFQpfsADl6LT8OMsiIYj7PUFBRRDiET4WyWsd1.."
    )

    /** 登录用户名 */
    const val LOGIN_USER = "tb62288171"
}
