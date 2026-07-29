#Requires AutoHotkey v2.0
; Alternates the physical right mouse button:
;   1st press -> real right click
;   2nd press -> middle click
;   3rd press -> right click ... and so on.
; If more than 2 seconds pass between presses, it resets to a right click.

toggle := false        ; false = next press is a right click, true = next is middle click
lastPress := 0         ; tick count (ms) of the previous press
resetMs := 2000        ; idle time before resetting to right click

*RButton::
{
    global toggle, lastPress, resetMs

    ; If it's been longer than resetMs since the last press, start fresh.
    if (A_TickCount - lastPress > resetMs)
        toggle := false

    if (toggle)
        Click("Middle")   ; middle click
    else
        Click("Right")    ; right click

    toggle := !toggle
    lastPress := A_TickCount
}
