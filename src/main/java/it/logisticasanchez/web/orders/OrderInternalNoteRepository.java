package it.logisticasanchez.web.orders;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderInternalNoteRepository extends JpaRepository<OrderInternalNote, Long> {}
