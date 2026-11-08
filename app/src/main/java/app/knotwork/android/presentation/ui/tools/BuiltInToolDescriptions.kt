package app.knotwork.android.presentation.ui.tools

import app.knotwork.android.R

/**
 * The string resource of each built-in tool's description, by tool name.
 *
 * A tool's own `description` is what a model reads, so it stays English whatever
 * language the app is in. The screens a person reads show this translated copy
 * instead. A tool that is not listed here (a discovered AppFunction, a tool of an
 * MCP server) came with a description written by someone else, and is shown as it
 * came.
 *
 * `BuiltInToolDescriptionsTest` fails a built-in tool without an entry and an
 * English string that differs from what the model reads.
 */
internal val BUILT_IN_TOOL_DESCRIPTIONS: Map<String, Int> = mapOf(
    "append_file" to R.string.tool_description_append_file,
    "delegate_task" to R.string.tool_description_delegate_task,
    "delete_file" to R.string.tool_description_delete_file,
    "edit_file" to R.string.tool_description_edit_file,
    "find_files" to R.string.tool_description_find_files,
    "http_request" to R.string.tool_description_http_request,
    "list_files" to R.string.tool_description_list_files,
    "read_file" to R.string.tool_description_read_file,
    "schedule_task" to R.string.tool_description_schedule_task,
    "search_tool" to R.string.tool_description_search_tool,
    "write_file" to R.string.tool_description_write_file,
)
