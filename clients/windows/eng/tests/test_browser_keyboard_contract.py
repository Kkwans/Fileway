"""Static routing guards; actual input/open behavior is tested by the Windows image smoke."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "src" / "Fileway.App"


class BrowserKeyboardContractTests(unittest.TestCase):
    def test_both_file_views_intercept_enter_before_item_class_handling(self):
        tree = ET.parse(APP / "MainPage.xaml")
        name = "{http://schemas.microsoft.com/winfx/2006/xaml}Name"
        views = [node for node in tree.iter() if node.attrib.get(name) in ("FileList", "FileGrid")]
        self.assertEqual(len(views), 2)
        for view in views:
            self.assertEqual(view.attrib["PreviewKeyDown"], "FileList_PreviewKeyDown")
            self.assertEqual(view.attrib["KeyDown"], "FileList_KeyDown")

    def test_only_one_enter_open_route_and_no_repeat_open(self):
        source = (APP / "MainPage.Keyboard.cs").read_text()
        self.assertIn("e.Key != VirtualKey.Enter", source)
        self.assertIn("!ReferenceEquals(sender, ActiveFiles)", source)
        self.assertIn("IsTextInput(e.OriginalSource", source)
        self.assertIn("source is ButtonBase", source)
        self.assertIn("SelectorItem { Content: FileRow row }", source)
        self.assertIn("e.KeyStatus.WasKeyDown", source)
        self.assertIn("finally { _keyboardOpenPending = false; }", source)
        self.assertLess(source.index("e.Handled = true"), source.index("await OpenSelectedAsync"))
        page = (APP / "MainPage.xaml.cs").read_text()
        body = page.split("private void FileList_KeyDown", 1)[1].split("private void Details_Click", 1)[0]
        self.assertNotIn("VirtualKey.Enter", body)
        for key in ("VirtualKey.F2", "VirtualKey.Delete", "VirtualKey.Escape", "VirtualKey.A"):
            self.assertIn(key, body)

    def test_modified_enter_remains_owned_by_other_controls(self):
        source = (APP / "MainPage.Keyboard.cs").read_text()
        for modifier in ("VirtualKey.Control", "VirtualKey.Menu", "VirtualKey.Shift"):
            self.assertIn(modifier, source)


if __name__ == "__main__":
    unittest.main()
