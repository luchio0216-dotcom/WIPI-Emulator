// wie_cli/src/database.rs를 base 경로만 주입받도록 옮긴 것.
use std::{fs, path::PathBuf};

use wie_backend::RecordId;

pub struct FsDatabaseRepository {
    base_path: PathBuf,
}

impl FsDatabaseRepository {
    pub fn new(base_path: PathBuf) -> Self {
        Self { base_path }
    }

    fn get_path_for_database(&self, name: &str, app_id: &str) -> PathBuf {
        let sanitized_app_id: String = app_id.chars().filter(|c| !matches!(c, '/' | '\\' | '\0')).collect();
        let app_id = if sanitized_app_id.is_empty() || sanitized_app_id == "." || sanitized_app_id == ".." { "_" } else { &sanitized_app_id };

        let name: String = name.chars().map(|c| if matches!(c, '\\' | '\0') { '_' } else { c }).collect();
        let mut normalized_name = PathBuf::new();
        for segment in name.trim_start_matches('/').split('/') {
            match segment { "" | "." => {}, ".." => normalized_name.push("_"), segment => normalized_name.push(segment) }
        }
        if normalized_name.as_os_str().is_empty() { normalized_name.push("_"); }
        self.base_path.join(app_id).join(normalized_name)
    }
}

#[async_trait::async_trait]
impl wie_backend::DatabaseRepository for FsDatabaseRepository {
    async fn open(&self, name: &str, app_id: &str) -> Box<dyn wie_backend::Database> {
        let path = self.get_path_for_database(name, app_id);
        tracing::info!(target: "inotia_db", "DB open name={name:?} app_id={app_id:?} path={}", path.display());
        Box::new(Database::new(path).unwrap())
    }

    async fn exists(&self, name: &str, app_id: &str) -> bool {
        let path = self.get_path_for_database(name, app_id);
        let exists = path.exists();
        tracing::info!(target: "inotia_db", "DB exists name={name:?} app_id={app_id:?} path={} -> {exists}", path.display());
        exists
    }

    async fn delete(&self, name: &str, app_id: &str) -> bool {
        let path = self.get_path_for_database(name, app_id);
        tracing::info!(target: "inotia_db", "DB delete name={name:?} app_id={app_id:?} path={}", path.display());
        match fs::remove_dir_all(path) {
            Ok(()) => true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
            Err(e) => { tracing::warn!("Failed to delete database: {e}"); false }
        }
    }
}

pub struct Database { base_path: PathBuf }

impl Database {
    pub fn new(base_path: PathBuf) -> anyhow::Result<Self> {
        fs::create_dir_all(&base_path)?;
        Ok(Self { base_path })
    }

    fn find_empty_record_id(&self) -> RecordId {
        let mut record_id = 1;
        loop {
            let path = self.base_path.join(record_id.to_string());
            if !path.exists() { return record_id; }
            record_id += 1;
        }
    }

    fn get_path_for_record(&self, id: RecordId) -> PathBuf { self.base_path.join(id.to_string()) }
}

#[async_trait::async_trait]
impl wie_backend::Database for Database {
    async fn next_id(&self) -> RecordId {
        let id = self.find_empty_record_id();
        tracing::info!(target: "inotia_db", "DB next_id path={} -> {id}", self.base_path.display());
        id
    }

    async fn add(&mut self, data: &[u8]) -> RecordId {
        let id = self.find_empty_record_id();
        let path = self.get_path_for_record(id);
        tracing::info!(target: "inotia_db", "DB add path={} id={id} len={} prefix={:02x?}", self.base_path.display(), data.len(), &data[..data.len().min(16)]);
        fs::write(path, data).unwrap();
        id
    }

    async fn get(&self, id: RecordId) -> Option<Vec<u8>> {
        let result = fs::read(self.get_path_for_record(id)).ok();
        match &result {
            Some(data) => tracing::info!(target: "inotia_db", "DB get path={} id={id} -> len={} prefix={:02x?}", self.base_path.display(), data.len(), &data[..data.len().min(16)]),
            None => tracing::info!(target: "inotia_db", "DB get path={} id={id} -> missing", self.base_path.display()),
        }
        result
    }

    async fn set(&mut self, id: RecordId, data: &[u8]) -> bool {
        tracing::info!(target: "inotia_db", "DB set path={} id={id} len={} prefix={:02x?}", self.base_path.display(), data.len(), &data[..data.len().min(16)]);
        fs::write(self.get_path_for_record(id), data).is_ok()
    }

    async fn delete(&mut self, id: RecordId) -> bool {
        tracing::info!(target: "inotia_db", "DB delete-record path={} id={id}", self.base_path.display());
        fs::remove_file(self.get_path_for_record(id)).is_ok()
    }

    async fn get_record_ids(&self) -> Vec<RecordId> {
        let ids: Vec<RecordId> = fs::read_dir(&self.base_path).unwrap().filter(|x| x.as_ref().unwrap().path().is_file()).map(|x| x.unwrap().file_name().to_str().unwrap().parse().unwrap()).collect();
        tracing::info!(target: "inotia_db", "DB record_ids path={} -> {:?}", self.base_path.display(), ids);
        ids
    }
}
