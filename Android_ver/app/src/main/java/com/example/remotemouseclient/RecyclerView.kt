package com.example.remotemouseclient

import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Adapter
class ItemAdapter(
    private var itemList: List<Host>, // lista usada para atualizar RecyclerView
    private val onItemClick: (Host, Int) -> Unit //callback de clique
) : RecyclerView.Adapter<ItemAdapter.ItemViewHolder>()
{
    // ViewHolder: guarda as referências dos elementos de cada item
    class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // textView do texto ip do item
        val textIP: TextView = itemView.findViewById(R.id.textIP)
        // textView do texto status do item
        val textStatus: TextView = itemView.findViewById(R.id.textStatus)
        // background do item
        val itemBackground: ImageView = itemView.findViewById(R.id.itemBackground)
    }

    // criar as Views (inflando o layout do item)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_list, parent, false)
        return ItemViewHolder(view)
    }

    // preencher os dados em cada item
    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = itemList[position]
        holder.textIP.text = item.ip
        holder.textStatus.text = item.status

        // mudar reação e aparência do item de acordo com o status
        if(item.status == "Conectado!"){
            // negar cliques do usuário
            holder.itemView.setOnClickListener(null)
            // mudar background do item
            holder.itemBackground.setImageResource(R.drawable.connected_item_list_background)
        }else{
            // configurar o que o clique vai fazer
            holder.itemView.setOnClickListener {
                onItemClick(item, position)
            }
            // mudar background do item
            holder.itemBackground.setImageResource(R.drawable.item_list_background)
        }
    }

    // retorna a quantidade de itens
    override fun getItemCount(): Int = itemList.size

    // função para atualizar a lista inteira do RecyclerView
    @SuppressLint("NotifyDataSetChanged")
    fun updateData(newList: List<Host>){
        itemList = newList
        notifyDataSetChanged()
        Log.d("UI", "RecyclerView inteiro atualizado")
    }

    // função para atualizar os dados de apenas um item no RecyclerView
    fun updateItem(pos: Int, status: String){
        if (pos in itemList.indices){
            itemList[pos].status = status
            notifyItemChanged(pos)
            Log.d("UI", "Item na posição $pos do RecyclerView atualizado")
        }
    }
}