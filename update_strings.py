import xml.etree.ElementTree as ET

def add_string(file_path, name, value):
    tree = ET.parse(file_path)
    root = tree.getroot()
    for child in root:
        if child.attrib.get('name') == name:
            child.text = value
            tree.write(file_path, encoding='utf-8', xml_declaration=True)
            return
    new_str = ET.Element('string', {'name': name})
    new_str.text = value
    root.append(new_str)
    # Just a simple write, no pretty formatting needed
    tree.write(file_path, encoding='utf-8', xml_declaration=True)

add_string("app/src/main/res/values/strings.xml", "txt_scan_doc", "Scan Document")
add_string("app/src/main/res/values-ar/strings.xml", "txt_scan_doc", "مسح مستند")

add_string("app/src/main/res/values/strings.xml", "txt_scan_id_card", "Scan ID Card / Passport")
add_string("app/src/main/res/values-ar/strings.xml", "txt_scan_id_card", "مسح بطاقة هوية / جواز سفر")

add_string("app/src/main/res/values/strings.xml", "txt_rename_folder", "Rename Folder")
add_string("app/src/main/res/values-ar/strings.xml", "txt_rename_folder", "إعادة تسمية المجلد")

add_string("app/src/main/res/values/strings.xml", "txt_new_folder_name", "New Folder Name")
add_string("app/src/main/res/values-ar/strings.xml", "txt_new_folder_name", "اسم المجلد الجديد")
