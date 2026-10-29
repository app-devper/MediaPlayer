package com.appdevper.mediaplayer.data.server

import com.appdevper.mediaplayer.data.upnp.host.Scpd

/** The ContentDirectory SCPD served by the built-in media server. */
internal object Descriptions {

    val contentDirectory: String = Scpd.document(
        actions = Scpd.action("GetSearchCapabilities", Scpd.arg("SearchCaps", "out", "SearchCapabilities")) +
            Scpd.action("GetSortCapabilities", Scpd.arg("SortCaps", "out", "SortCapabilities")) +
            Scpd.action("GetSystemUpdateID", Scpd.arg("Id", "out", "SystemUpdateID")) +
            Scpd.action(
                "Browse",
                Scpd.arg("ObjectID", "in", "A_ARG_TYPE_ObjectID"),
                Scpd.arg("BrowseFlag", "in", "A_ARG_TYPE_BrowseFlag"),
                Scpd.arg("Filter", "in", "A_ARG_TYPE_Filter"),
                Scpd.arg("StartingIndex", "in", "A_ARG_TYPE_Index"),
                Scpd.arg("RequestedCount", "in", "A_ARG_TYPE_Count"),
                Scpd.arg("SortCriteria", "in", "A_ARG_TYPE_SortCriteria"),
                Scpd.arg("Result", "out", "A_ARG_TYPE_Result"),
                Scpd.arg("NumberReturned", "out", "A_ARG_TYPE_Count"),
                Scpd.arg("TotalMatches", "out", "A_ARG_TYPE_Count"),
                Scpd.arg("UpdateID", "out", "A_ARG_TYPE_UpdateID"),
            ),
        variables = Scpd.variable("SearchCapabilities", "string") +
            Scpd.variable("SortCapabilities", "string") +
            Scpd.variable("SystemUpdateID", "ui4", events = true) +
            Scpd.variable("A_ARG_TYPE_ObjectID", "string") +
            Scpd.variable("A_ARG_TYPE_BrowseFlag", "string", allowed = listOf("BrowseMetadata", "BrowseDirectChildren")) +
            Scpd.variable("A_ARG_TYPE_Filter", "string") +
            Scpd.variable("A_ARG_TYPE_Index", "ui4") +
            Scpd.variable("A_ARG_TYPE_Count", "ui4") +
            Scpd.variable("A_ARG_TYPE_SortCriteria", "string") +
            Scpd.variable("A_ARG_TYPE_Result", "string") +
            Scpd.variable("A_ARG_TYPE_UpdateID", "ui4"),
    )
}
