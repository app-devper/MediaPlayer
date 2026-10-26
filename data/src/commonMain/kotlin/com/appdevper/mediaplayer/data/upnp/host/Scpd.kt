package com.appdevper.mediaplayer.data.upnp.host

/** Builders for UPnP service description (SCPD) documents. */
internal object Scpd {
    fun arg(name: String, dir: String, variable: String) =
        "<argument><name>$name</name><direction>$dir</direction><relatedStateVariable>$variable</relatedStateVariable></argument>"

    fun action(name: String, vararg args: String) =
        "<action><name>$name</name><argumentList>${args.joinToString("")}</argumentList></action>"

    fun variable(name: String, type: String, events: Boolean = false, allowed: List<String> = emptyList()) = buildString {
        append("<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>")
        if (allowed.isNotEmpty()) append("<allowedValueList>${allowed.joinToString("") { "<allowedValue>$it</allowedValue>" }}</allowedValueList>")
        append("</stateVariable>")
    }

    fun document(actions: String, variables: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<actionList>$actions</actionList><serviceStateTable>$variables</serviceStateTable></scpd>"
}
