import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';

class MemoryStore {
  Database? _database;

  Future<void> initialize() async {
    if (_database != null) return;

    final directory = await getApplicationDocumentsDirectory();
    _database = await openDatabase(
      path.join(directory.path, 'akuji_memory.db'),
      version: 1,
      onCreate: (database, _) async {
        await database.execute('''
          CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at INTEGER NOT NULL
          )
        ''');
        await database.execute('''
          CREATE TABLE memory_facts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fact_key TEXT NOT NULL UNIQUE,
            fact_value TEXT NOT NULL,
            updated_at INTEGER NOT NULL
          )
        ''');
      },
    );
  }

  Future<void> saveMessage({
    required String role,
    required String content,
  }) async {
    final database = _requireDatabase();
    await database.insert('messages', {
      'role': role,
      'content': content.trim(),
      'created_at': DateTime.now().millisecondsSinceEpoch,
    });
  }

  Future<String> recentTranscript({int limit = 12}) async {
    final database = _requireDatabase();
    final rows = await database.query(
      'messages',
      columns: ['role', 'content'],
      orderBy: 'id DESC',
      limit: limit,
    );

    return rows.reversed.map((row) {
      final role = row['role'] == 'user' ? 'Peachez' : 'AKUJI';
      return '$role: ${row['content']}';
    }).join('\n');
  }

  Future<int> messageCount() async {
    final database = _requireDatabase();
    final result = await database.rawQuery(
      'SELECT COUNT(*) AS message_count FROM messages',
    );
    return Sqflite.firstIntValue(result) ?? 0;
  }

  Database _requireDatabase() {
    final database = _database;
    if (database == null) {
      throw StateError('AKUJI memory has not been initialized.');
    }
    return database;
  }
}
