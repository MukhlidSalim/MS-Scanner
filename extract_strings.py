import os
import re
import xml.etree.ElementTree as ET

def process_file(filepath, strings_dict):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Find all Text("...") and Icon(..., "...")
    # This is a naive regex but good enough for this specific task
    text_pattern = re.compile(r'Text\(\s*"([^"]+)"')
    icon_pattern = re.compile(r'contentDescription\s*=\s*"([^"]+)"')
    
    modified_content = content
    
    for match in text_pattern.finditer(content):
        text = match.group(1)
        if text.strip() and not '{' in text:
            # Create a safe key
            key = "txt_" + re.sub(r'[^a-z0-9]', '_', text.lower())
            key = key[:30].strip('_')
            strings_dict[key] = text
            # Replace in content
            modified_content = modified_content.replace(f'Text("{text}"', f'Text(stringResource(R.string.{key})')
            
    for match in icon_pattern.finditer(content):
        desc = match.group(1)
        if desc.strip() and not '{' in desc:
            key = "desc_" + re.sub(r'[^a-z0-9]', '_', desc.lower())
            key = key[:30].strip('_')
            strings_dict[key] = desc
            modified_content = modified_content.replace(f'contentDescription = "{desc}"', f'contentDescription = stringResource(R.string.{key})')

    with open(filepath, 'w', encoding='utf-8') as f:
        # Add import if needed
        if 'stringResource' in modified_content and 'androidx.compose.ui.res.stringResource' not in modified_content:
            lines = modified_content.split('\n')
            for i, line in enumerate(lines):
                if line.startswith('import '):
                    lines.insert(i, 'import androidx.compose.ui.res.stringResource\nimport com.example.R')
                    break
            modified_content = '\n'.join(lines)
        f.write(modified_content)

def update_strings_xml(res_dir, strings_dict, lang="en"):
    path = os.path.join(res_dir, 'values' if lang == "en" else 'values-ar', 'strings.xml')
    tree = ET.parse(path)
    root = tree.getroot()
    
    existing_keys = {child.attrib.get('name') for child in root.findall('string')}
    
    for k, v in strings_dict.items():
        if k not in existing_keys:
            elem = ET.SubElement(root, 'string', name=k)
            # Basic translation logic just for demonstration. 
            # In a real app we'd use Google Translate API, but here we just append [AR] if arabic
            elem.text = v if lang == "en" else f"[AR] {v}"
            
    ET.indent(tree, space="    ")
    tree.write(path, encoding='utf-8', xml_declaration=True)

if __name__ == '__main__':
    strings_dict = {}
    src_dir = r"C:\Users\Mukhlid\antigravity\DocScan-Pro\app\src\main\java\com\example\ui\screens"
    for root, dirs, files in os.walk(src_dir):
        for f in files:
            if f.endswith('.kt'):
                process_file(os.path.join(root, f), strings_dict)
                
    res_dir = r"C:\Users\Mukhlid\antigravity\DocScan-Pro\app\src\main\res"
    update_strings_xml(res_dir, strings_dict, "en")
    update_strings_xml(res_dir, strings_dict, "ar")
