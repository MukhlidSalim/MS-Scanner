import re

def insert_strings(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    new_strings = """
    <string name="txt_backup_restore">Backup &amp; Restore</string>
    <string name="txt_create_backup">Create Backup (ZIP)</string>
    <string name="txt_create_backup_desc">Export your documents to a safe location</string>
    <string name="txt_restore_backup">Restore Backup</string>
    <string name="txt_restore_backup_desc">Import documents from a ZIP file</string>
    <string name="txt_creating_backup">Creating backup...</string>
    <string name="txt_restoring_backup">Restoring backup...</string>
    <string name="txt_backup_success">Backup created successfully</string>
    <string name="txt_restore_success">Restore completed successfully</string>
    <string name="txt_backup_failed">Backup failed</string>
    <string name="txt_restore_failed">Restore failed</string>
"""

    content = content.replace('</resources>', new_strings + '</resources>')

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

insert_strings('app/src/main/res/values/strings.xml')
insert_strings('app/src/main/res/values-ar/strings.xml')
