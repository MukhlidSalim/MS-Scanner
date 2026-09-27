import re

def remove_dup_cancel(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()
        
    new_lines = []
    seen_cancel = False
    for line in lines:
        if 'name="txt_cancel"' in line:
            if not seen_cancel:
                seen_cancel = True
                new_lines.append(line)
        else:
            new_lines.append(line)
            
    with open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(new_lines)

remove_dup_cancel('app/src/main/res/values/strings.xml')
remove_dup_cancel('app/src/main/res/values-ar/strings.xml')
