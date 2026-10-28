"""MIG-02 (Q-55, 2026-10-28): the 12b §5.5 mapping of generate_import_sql.py on a synthetic dump.

    python3 -I -m unittest discover -s tools/legacy-import -p 'test_*.py'
"""

import gzip
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from generate_import_sql import months_ago  # noqa: E402
from mysqldump import read_dump  # noqa: E402

GENERATOR = os.path.join(HERE, "generate_import_sql.py")

DDL = """
CREATE TABLE `soggetti` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cap` varchar(255) NOT NULL,
  `citta` varchar(255) NOT NULL,
  `codice_verifica` varchar(255) DEFAULT NULL,
  `create_tms` datetime(6) NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `email` varchar(255) NOT NULL,
  `indirizzo` varchar(255) NOT NULL,
  `provincia` varchar(255) NOT NULL,
  `ragione_sociale` varchar(255) NOT NULL,
  `stato` varchar(255) NOT NULL,
  `telefono` varchar(255) NOT NULL,
  `update_tms` datetime(6) DEFAULT NULL,
  `update_user` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;
CREATE TABLE `spedizioni` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cap` varchar(255) NOT NULL,
  `citta` varchar(255) NOT NULL,
  `codice` varchar(255) NOT NULL,
  `codice_documento_trasporto` varchar(255) DEFAULT NULL,
  `confirmation_ts` datetime(6) DEFAULT NULL,
  `create_tms` datetime(6) NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `delivery_ts` datetime(6) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `importo_assicurato` double DEFAULT NULL,
  `importo_contrassegno` double DEFAULT NULL,
  `indirizzo` varchar(255) NOT NULL,
  `note` varchar(255) DEFAULT NULL,
  `provincia` varchar(255) NOT NULL,
  `ragione_sociale` varchar(255) NOT NULL,
  `servizio_accessorio` varchar(255) NOT NULL,
  `shipping_ts` datetime(6) DEFAULT NULL,
  `stato` varchar(255) NOT NULL,
  `telefono` varchar(255) DEFAULT NULL,
  `tracking` varchar(255) NOT NULL,
  `update_tms` datetime(6) DEFAULT NULL,
  `update_user` varchar(255) DEFAULT NULL,
  `mittente_id` bigint NOT NULL,
  `importo` double DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;
CREATE TABLE `dettaglio_colli` (
  `id` bigint NOT NULL,
  `colli` int DEFAULT NULL,
  `create_tms` datetime(6) NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `epal` int DEFAULT NULL,
  `pallet` int DEFAULT NULL,
  `peso` double NOT NULL,
  `update_tms` datetime(6) DEFAULT NULL,
  `update_user` varchar(255) DEFAULT NULL,
  `volume` double NOT NULL,
  `spedizione_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;
CREATE TABLE `pallet` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `altezza` double NOT NULL,
  `create_tms` datetime(6) NOT NULL,
  `create_user` varchar(255) NOT NULL,
  `larghezza` double NOT NULL,
  `lunghezza` double NOT NULL,
  `peso` double NOT NULL,
  `type` varchar(255) NOT NULL,
  `update_tms` datetime(6) DEFAULT NULL,
  `update_user` varchar(255) DEFAULT NULL,
  `dettaglio_colli_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB;
"""

T0 = "'2026-05-04 08:14:03.512000'"
T1 = "'2026-05-04 13:40:11.004000'"
T2 = "'2026-05-06 09:00:00.000000'"
# Synthetic rows only: invented names, .example addresses, TEST codes (CLAUDE.md).
DATA = f"""
INSERT INTO `soggetti` VALUES (1,'20831','Seregno',NULL,'2024-06-03 08:00:00.000000','ADMIN',' Uno@Cliente.example','Via Prova 1','MB','Alfa Test Srl','REGISTERED','0362 000 001',NULL,NULL),(2,'20900','Monza','x','2024-07-22 00:11:13.000000','CUSTOMER','due@cliente.example','Via Prova 2','MB','Beta Test','NEW','3330000002',NULL,NULL),(3,'20900','Monza','y','2024-07-22 10:21:39.000000','CUSTOMER','DUE@cliente.example','Via Prova 3','MB','Beta Test Bis','NEW','3330000002',NULL,NULL),(4,'20100','Milano','z','2024-06-09 10:00:00.000000','CUSTOMER','quattro@cliente.example','Via Prova 4','MI','Delta Test','NEW','3330000004','2024-08-30 10:00:00.000000','ROLE_ADMIN'),(5,'20100','Milano','w','2025-03-11 10:00:00.000000','CUSTOMER','cinque@cliente.example','Via Prova 5','Milano','Epsilon Test','REGISTERED','3330000005','2025-03-11 11:00:00.000000','ROLE_ADMIN');
INSERT INTO `spedizioni` VALUES (10,'20100','Milano','TEST-0001','DDT-1',{T1},{T0},'CUSTOMER','2026-05-05 16:00:00.000000','cinque@cliente.example',NULL,NULL,'Via Destinatario 1','',  'MI','Gamma Destinatario','Nessun servizio accessorio','2026-05-05 08:00:00.000000','REGISTERED','3330000010','Consegnato',{T2},'ROLE_ADMIN',5,180.505),(11,'20100','Milano','\\tTEST-0002 ',NULL,{T1},{T0},'CUSTOMER',NULL,'cinque@cliente.example',99.99,99.99,'Via Destinatario 2','Scarico con sponda','MI','Gamma Destinatario','Sponda idraulica','2026-05-05 08:00:00.000000','REGISTERED',NULL,'Assegnato',{T2},'ROLE_ADMIN',5,NULL),(12,'20100','Milano','TEST-0003',NULL,{T1},'2026-04-27 23:59:59.999999','CUSTOMER',NULL,'cinque@cliente.example',NULL,NULL,'Via Destinatario 3','','MI','Gamma Destinatario','Nessun servizio accessorio',NULL,'REGISTERED',NULL,'Presa in carico',{T2},'ROLE_ADMIN',5,10);
INSERT INTO `dettaglio_colli` VALUES (100,NULL,{T0},'CUSTOMER',2,1,1040,NULL,NULL,4,10),(101,NULL,{T0},'CUSTOMER',0,1,50,NULL,NULL,0,11),(102,NULL,{T0},'CUSTOMER',0,1,50,NULL,NULL,0,12);
INSERT INTO `pallet` VALUES (1000,150,{T0},'CUSTOMER',80,120,420,'EPAL',NULL,NULL,100),(1001,150,{T0},'CUSTOMER',80,120,420,'EPAL',NULL,NULL,100),(1002,100,{T0},'CUSTOMER',100,120,200,'PALLET',NULL,NULL,100),(1003,50,{T0},'CUSTOMER',50,50,50,'PALLET',NULL,NULL,101),(1004,50,{T0},'CUSTOMER',50,50,50,'PALLET',NULL,NULL,102);
INSERT INTO `users` VALUES (1,'staff.test');
"""


class GenerateImportSqlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.dump = os.path.join(cls.tmp.name, "lsdb_synthetic.sql.gz")
        with gzip.open(cls.dump, "wt", encoding="utf-8") as fh:
            fh.write(DDL + DATA)
        cls.out = os.path.join(cls.tmp.name, "import.sql")
        run = subprocess.run(
            [sys.executable, "-I", GENERATOR, cls.dump, cls.out, "--cutoff", "2026-04-28 00:00:00"],
            capture_output=True,
            text=True,
        )
        cls.report = run.stdout
        cls.returncode = run.returncode
        cls.stderr = run.stderr
        with open(cls.out, encoding="utf-8") as fh:
            cls.sql = fh.read()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_dump_reader_decodes_escapes_and_nulls(self):
        rows = read_dump(self.dump)["spedizioni"]["rows"]
        self.assertEqual(rows[1]["codice"], "\tTEST-0002 ")
        self.assertIsNone(rows[1]["importo"])
        self.assertEqual(rows[0]["importo"], 180.505)

    def test_runs_and_counts(self):
        self.assertEqual(self.returncode, 0, self.stderr)
        # customers 1, 3, 4, 5 (2 is the older duplicate); orders 10 and 11 (12 is before the cut-off)
        self.assertIn('"customers": 4, "orders": 2, "order_lines": 3, "tracking_events": 4, "internal_notes": 1', self.report)
        self.assertIn("DQ-01 legacy ids [2, 3]", self.report)
        self.assertNotIn("staff.test", self.sql)

    def test_customer_mapping(self):
        # REGISTERED created by staff -> ACTIVE, BACKOFFICE, approved at registration; e-mail normalised; phone +39
        self.assertIn("(1, 'uno@cliente.example', 'Alfa Test Srl', 'Via Prova 1', 'Seregno', 'MB', '20831', '+390362000001', 'ACTIVE', '2024-06-03 08:00:00.000000+00', '2024-06-03 08:00:00.000000+00', '2024-06-03 08:00:00.000000+00', NULL, 'BACKOFFICE'", self.sql)
        self.assertIn("(3, 'due@cliente.example', 'Beta Test Bis'", self.sql)
        self.assertNotIn("'Beta Test',", self.sql)
        self.assertIn("'DECLINED', NULL, '2024-06-09 10:00:00.000000+00', NULL, '2024-08-30 10:00:00.000000+00', 'CUSTOMER_AREA'", self.sql)
        self.assertIn("'+393330000005', 'ACTIVE', '2025-03-11 11:00:00.000000+00'", self.sql)

    def test_order_mapping(self):
        # approved: marker temp code, trimmed code, amount half-up, DDT marker when missing, service by name
        self.assertIn("(10, 5, 'APPROVED', 'LEGACY-10', 'TEST-0001', 'DDT-1', NULL, NULL, NULL, '180.51', '1040.00', '4.08', 5, '2026-05-05 16:00:00.000000+00'", self.sql)
        self.assertIn("(11, 5, 'APPROVED', 'LEGACY-11', 'TEST-0002', 'N/D', NULL, 'SPONDA_IDR', NULL, '99.99', '50.00', '0.13', 4, '2026-05-06 09:00:00.000000+00'", self.sql)
        self.assertIn("'+393330000010'", self.sql)
        self.assertNotIn("TEST-0003", self.sql)

    def test_lines_events_notes(self):
        # two equal consecutive EPAL units -> one line of 2; the PALLET unit -> GENERIC
        self.assertIn("(10, 1, 'EPAL', 150, 120, 80, '420.00', 2, '840.00', '2.88')", self.sql)
        self.assertIn("(10, 2, 'GENERIC', 100, 120, 100, '200.00', 1, '200.00', '1.20')", self.sql)
        self.assertIn("(10, 1, '2026-05-04 13:40:11.004000+00', 'APPROVAL')", self.sql)
        self.assertIn("(10, 5, '2026-05-05 16:00:00.000000+00', 'STAFF')", self.sql)
        # ASSEGNATO has no timestamp of its own: the last update, estimated
        self.assertIn("(11, 4, '2026-05-06 09:00:00.000000+00', 'STAFF')", self.sql)
        self.assertIn("(11, 'Scarico con sponda', '2026-05-06 09:00:00.000000+00')", self.sql)

    def test_transaction_guards(self):
        self.assertIn("BEGIN;", self.sql)
        self.assertTrue(self.sql.rstrip().endswith("COMMIT;"))
        self.assertIn("already loaded (audit_log action legacy.import)", self.sql)
        self.assertNotRegex(self.sql, r"INSERT INTO (customer|shipment_order|order_line|tracking_event|customer_user) \(id")


class OptionsTest(unittest.TestCase):
    def test_months_ago_keeps_the_day_or_takes_the_last_of_the_month(self):
        import datetime

        self.assertEqual(months_ago(datetime.date(2026, 10, 28), 6), datetime.date(2026, 4, 28))
        self.assertEqual(months_ago(datetime.date(2026, 8, 31), 6), datetime.date(2026, 2, 28))
        self.assertEqual(months_ago(datetime.date(2026, 3, 15), 6), datetime.date(2025, 9, 15))

    def test_excluded_customers_and_their_orders_are_left_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            dump = os.path.join(tmp, "lsdb_synthetic.sql.gz")
            with gzip.open(dump, "wt", encoding="utf-8") as fh:
                fh.write(DDL + DATA)
            exclude = os.path.join(tmp, "exclude.txt")
            with open(exclude, "w", encoding="utf-8") as fh:
                fh.write("# synthetic\n  CINQUE@cliente.example \n\nnessuno@cliente.example\n")
            out = os.path.join(tmp, "import.sql")
            run = subprocess.run(
                [sys.executable, "-I", GENERATOR, dump, out, "--cutoff", "2026-04-28 00:00:00", "--exclude-emails", exclude],
                capture_output=True,
                text=True,
            )
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("customers excluded by --exclude-emails: 1", run.stdout)
            self.assertIn("exclusions matching no customer: 1", run.stdout)
            self.assertIn("orders of excluded customers in the window (not imported): 2", run.stdout)
            self.assertIn('"customers": 3, "orders": 0', run.stdout)
            with open(out, encoding="utf-8") as fh:
                sql = fh.read()
            self.assertNotIn("cinque@cliente.example", sql)
            self.assertNotIn("TEST-0001", sql)


if __name__ == "__main__":
    unittest.main()
