package dev.baudet.ui

/**
 * The two rules behind the reasoning dock, kept free of Swing so that they can be tested on
 * their own: whether the dock claims a row of the stack, and whether an arriving chunk is
 * allowed to drag the view to the bottom.
 */
object ThinkingLayout {

    /**
     * The dock claims a row only while there is reasoning to show and the reader has not
     * closed it. Closing during a turn therefore suppresses the remaining chunks of that turn
     * and nothing more: the next turn starts with the flag reset and may open again.
     */
    fun dockWanted(hasReasoning: Boolean, collapsed: Boolean): Boolean =
        hasReasoning && !collapsed

    /**
     * Whether an append should follow the tail. Only a bar already resting within [slack]
     * pixels of the bottom qualifies, so scrolling up to reread is never undone by the next
     * chunk that arrives.
     */
    fun followsTail(value: Int, extent: Int, maximum: Int, slack: Int): Boolean =
        maximum - (value + extent) <= slack
}
