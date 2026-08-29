mod workspace;

use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }
            let config_dir = app.path().app_config_dir()?;
            std::fs::create_dir_all(&config_dir)?;
            app.manage(workspace::WorkspaceState::new(
                config_dir.join("workspaces.json"),
            ));
            workspace::start_grant_reaper(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed) {
                window
                    .state::<workspace::WorkspaceState>()
                    .cleanup_window(window.label());
            }
        })
        .invoke_handler(tauri::generate_handler![
            workspace::authorize_workspace,
            workspace::revoke_workspace,
            workspace::pick_workspace,
            workspace::list_workspaces,
            workspace::upload_attachments,
            workspace::forget_workspace,
            workspace::list_directory,
            workspace::read_file,
            workspace::workspace_context,
            workspace::workspace_file_index,
            workspace::git_status,
            workspace::git_diff,
            workspace::terminal_create,
            workspace::terminal_write,
            workspace::terminal_resize,
            workspace::terminal_close,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
