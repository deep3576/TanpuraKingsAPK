// ============================================================
// CarPlaySceneDelegate.swift  —  PENDING ENTITLEMENT APPROVAL
// ============================================================
//
// CPTemplateApplicationAudioScene / CPTemplateApplicationAudioSceneDelegate
// are gated by the com.apple.developer.carplay-audio entitlement at the
// SDK level.  Apple removes those symbols from the CarPlay framework headers
// when building without the entitlement, so this file cannot be compiled
// until Apple approves the entitlement for this App ID.
//
// This file is intentionally excluded from the Xcode build target
// (no entry in the Sources build phase).  The full implementation is
// preserved below as comments so it can be un-commented and re-enabled
// in one step after approval.
//
// ── How to activate ─────────────────────────────────────────────────────────
//  1. Request the entitlement at:
//       https://developer.apple.com/contact/request/carplay
//     Choose "CarPlay Audio" for the entitlement type and provide your
//     bundle ID  com.kingsman.tanpurakings
//
//  2. Once Apple adds it to your App ID, open Xcode → Signing & Capabilities
//     and let Xcode regenerate the provisioning profile.
//
//  3. Un-comment the two lines in TanpuraKings.entitlements:
//       <key>com.apple.developer.carplay-audio</key>
//       <true/>
//
//  4. In Info.plist, restore UISceneConfigurations inside
//     UIApplicationSceneManifest:
//       <key>UISceneConfigurations</key>
//       <dict>
//           <key>CPTemplateApplicationAudioSceneSessionRoleApplication</key>
//           <array>
//               <dict>
//                   <key>UISceneConfigurationName</key>
//                   <string>CarPlay Audio</string>
//                   <key>UISceneDelegateClassName</key>
//                   <string>$(PRODUCT_MODULE_NAME).CarPlaySceneDelegate</string>
//               </dict>
//           </array>
//       </dict>
//
//  5. In project.pbxproj, add CarPlaySceneDelegate.swift back to the
//     PBXSourcesBuildPhase (Sources) section and link CarPlay.framework.
//
// ── Implementation (un-comment after step 5) ────────────────────────────────
//
// import CarPlay
// import MediaPlayer
//
// final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationAudioSceneDelegate {
//
//     private weak var interfaceController: CPInterfaceController?
//     private var listTemplate: CPListTemplate?
//
//     private let noteKeys   = ["c","csharp","d","dsharp","e","f","fsharp","g","gsharp","a","asharp","b"]
//     private let noteLabels = ["C","C#","D","D#","E","F","F#","G","G#","A","A#","B"]
//
//     func templateApplicationScene(
//         _ templateApplicationScene: CPTemplateApplicationAudioScene,
//         didConnect interfaceController: CPInterfaceController
//     ) {
//         self.interfaceController = interfaceController
//         NotificationCenter.default.addObserver(self, selector: #selector(notesChanged),
//             name: Notification.Name("tanpuraActiveNotesChanged"), object: nil)
//         let template = makeListTemplate()
//         listTemplate = template
//         interfaceController.setRootTemplate(template, animated: false, completion: nil)
//     }
//
//     func templateApplicationScene(
//         _ templateApplicationScene: CPTemplateApplicationAudioScene,
//         didDisconnect interfaceController: CPInterfaceController
//     ) {
//         NotificationCenter.default.removeObserver(self)
//         self.interfaceController = nil
//         listTemplate = nil
//     }
//
//     private func makeListTemplate() -> CPListTemplate {
//         CPListTemplate(title: "Tanpura Kings", sections: [makeSection()])
//     }
//
//     private func makeSection() -> CPListSection {
//         let active = AudioManager.shared.activeNoteNames
//         let items: [CPListItem] = zip(noteKeys, noteLabels).map { key, label in
//             let isActive = active.contains(key)
//             let item = CPListItem(
//                 text: label,
//                 detailText: isActive ? "▶  Playing — tap to stop" : "Tap to play"
//             )
//             item.accessoryType = .disclosureIndicator
//             item.handler = { [weak self] _, completion in
//                 self?.handleNoteTap(key: key)
//                 completion()
//             }
//             return item
//         }
//         return CPListSection(items: items, header: "Select a drone note", sectionIndexTitle: nil)
//     }
//
//     @objc private func notesChanged() {
//         guard let template = listTemplate else { return }
//         template.updateSections([makeSection()])
//         if AudioManager.shared.activeNoteNames.isEmpty {
//             interfaceController?.popToRootTemplate(animated: true, completion: nil)
//         }
//     }
//
//     private func handleNoteTap(key: String) {
//         let active = AudioManager.shared.activeNoteNames
//         if active.contains(key) {
//             AudioManager.shared.stopNote(key)
//         } else {
//             AudioManager.shared.playNote(key, masterVolume: 1.0)
//             DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
//                 guard let self = self else { return }
//                 if !(self.interfaceController?.topTemplate is CPNowPlayingTemplate) {
//                     self.interfaceController?.pushTemplate(CPNowPlayingTemplate.shared,
//                         animated: true, completion: nil)
//                 }
//             }
//         }
//     }
// }
