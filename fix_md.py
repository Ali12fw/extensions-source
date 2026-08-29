import re
import textwrap

def fix_markdown(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    lines = content.split('\n')
    new_lines = []
    
    # MD041: First line should be top level heading
    if lines and not lines[0].startswith('#'):
        lines[0] = '# ' + lines[0]
        
    for i, line in enumerate(lines):
        # MD029: fix ordered list prefix being parsed instead of headers
        # e.g., "1. PURPOSE" -> "## 1. PURPOSE"
        # "2. THE USER'S EXPLICIT GOAL" -> "## 2. THE USER'S EXPLICIT GOAL"
        # We look for lines matching: digit(s) dot space ALL_CAPS
        match = re.match(r'^(\d+\.)\s+([A-Z].*)$', line)
        if match and i > 0 and lines[i-1].strip() == '':
            # It's a header like "1. PURPOSE"
            line = '## ' + line
            
        new_lines.append(line)
        
    # MD013: line length. We can wrap lines that exceed 80 chars
    wrapped_lines = []
    in_code_block = False
    for line in new_lines:
        if line.startswith('```'):
            in_code_block = not in_code_block
            wrapped_lines.append(line)
            continue
            
        if in_code_block:
            wrapped_lines.append(line)
            continue
            
        if len(line) <= 80:
            wrapped_lines.append(line)
        else:
            # Only wrap if it's not a heading or a special markdown line (like ---)
            if line.startswith('#') or line.startswith('-') or line.startswith('>'):
                # Wrap but keep prefix? Markdownlint might complain if headers are > 80 chars. 
                # Actually, markdownlint rule MD013 ignores headings by default: "MD013 - Line length. Exception: headers, code blocks"
                wrapped_lines.append(line)
            else:
                wrapped = textwrap.wrap(line, width=80, break_long_words=False, break_on_hyphens=False)
                wrapped_lines.extend(wrapped)
                
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write('\n'.join(wrapped_lines))

if __name__ == "__main__":
    fix_markdown(r'E:\AndroidDev\projects\legacy-extensions-source\GEMINI_test.md')
