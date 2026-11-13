package dev.shulhi.rex

internal fun sameRowCells(first: List<TerminalCell>,second: List<TerminalCell>): Boolean {
    if(first===second) return true
    if(first.size!=second.size) return false
    return first.indices.all {index->
        val before=first[index];val after=second[index]
        before.x==after.x && before.width==after.width && before.text==after.text &&
            before.foreground==after.foreground && before.background==after.background &&
            before.attributes==after.attributes && before.hyperlink==after.hyperlink
    }
}

internal fun sameRowLinks(first: List<TerminalLink>,second: List<TerminalLink>): Boolean {
    if(first===second) return true
    if(first.size!=second.size) return false
    return first.indices.all {index->
        val before=first[index];val after=second[index]
        before.start==after.start && before.end==after.end && before.url==after.url && before.explicit==after.explicit
    }
}
