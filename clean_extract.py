with open("ChatScreen_recovered_from_other_convo.kt", "r") as f:
    lines = f.readlines()

in_code = False
extracted = []

for line in lines:
    if line.strip() == "package com.neveroff.ui.screens":
        in_code = True
    
    if in_code:
        # Check if the line is part of the next JSON log entry
        if line.startswith('{"step_index"') or line.startswith('"}') or line.startswith('The following is an <EPHEMERAL_MESSAGE>'):
            break
        # The line might be tab-indented because of tool output
        # Let's remove exactly 4 tabs if they exist
        if line.startswith('\t\t\t\t'):
            extracted.append(line[4:])
        else:
            extracted.append(line)

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
    f.writelines(extracted)
